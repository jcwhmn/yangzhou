package yangzhou.api.business.capability

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Capability
import yangzhou.persistence.Member
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.CapabilityRepository
import java.util.UUID

/** 能力自评:member 侧属性附着 (attribute, level?),未评级 level=null。 */
@Service
class CapabilityService(
    private val capabilities: CapabilityRepository,
    private val definitions: AttributeDefinitionRepository,
    private val memberService: MemberService,
    private val workspaceService: WorkspaceService,
    private val feasibilityService: yangzhou.api.business.feasibility.FeasibilityService,
) {
    data class CapabilityDto(
        val attribute: String,
        val level: Int?,
    )

    fun list(): List<CapabilityDto> = listFor(memberService.current())

    fun listFor(member: Member): List<CapabilityDto> {
        val names = definitionNames()
        return capabilities
            .findByMemberObjectId(
                member.objectId!!,
            ).map { CapabilityDto(names[it.attributeDefinitionObjectId] ?: "?", it.level) }
    }

    /** 按属性名 upsert;level=null 表示未评级,attribute=null 表示移除等级只保留 presence。 */
    @Transactional
    fun upsert(
        attribute: String,
        level: Int?,
    ): CapabilityDto = upsertFor(memberService.current(), attribute, level)

    @Transactional
    fun upsertFor(
        member: Member,
        attribute: String,
        level: Int?,
    ): CapabilityDto {
        if (level != null && level !in 1..4) throw BadRequestException("level 只能是 1–4 或不填")
        val memberObjectId = member.objectId!!
        val def =
            definitions.findByWorkspaceObjectIdAndName(workspaceService.required().objectId!!, attribute)
                ?: throw NotFoundException("词表中没有属性:$attribute(先建属性)")
        val defId = def.objectId!!
        val existing = capabilities.findByMemberObjectIdAndAttributeDefinitionObjectId(memberObjectId, defId)
        val saved =
            try {
                capabilities.save(
                    (existing ?: Capability(memberObjectId = memberObjectId, attributeDefinitionObjectId = defId))
                        .copy(level = level),
                )
            } catch (_: DataIntegrityViolationException) {
                throw ConflictException("能力写入冲突")
            }
        val dto = CapabilityDto(def.name, saved.level)
        feasibilityService.recomputeWorkspace() // V9-S1:能力变更影响全 workspace 冗余
        return dto
    }

    @Transactional
    fun delete(attribute: String) = deleteFor(memberService.current(), attribute)

    @Transactional
    fun deleteFor(
        member: Member,
        attribute: String,
    ) {
        val def =
            definitions.findByWorkspaceObjectIdAndName(workspaceService.required().objectId!!, attribute)
                ?: throw NotFoundException("词表中没有属性:$attribute")
        capabilities
            .findByMemberObjectIdAndAttributeDefinitionObjectId(member.objectId!!, def.objectId!!)
            ?.let { capabilities.delete(it) }
        feasibilityService.recomputeWorkspace() // V9-S1
    }

    private fun definitionNames(): Map<UUID, String> =
        definitions.findByWorkspaceObjectId(workspaceService.required().objectId!!).associate { it.objectId!! to it.name }
}

data class UpsertCapabilityRequest(
    @field:NotBlank val attribute: String,
    val level: Int? = null,
)
