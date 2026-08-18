package yangzhou.api.capability

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.member.MemberService
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.Capability
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
) {

    data class CapabilityDto(val attribute: String, val level: Int?)

    fun list(): List<CapabilityDto> {
        val member = memberService.current()
        val names = definitionNames()
        return capabilities.findByMemberId(member.id!!).map { CapabilityDto(names[it.attributeDefinitionId] ?: "?", it.level) }
    }

    /** 按属性名 upsert;level=null 表示未评级,attribute=null 表示移除等级只保留 presence。 */
    @Transactional
    fun upsert(attribute: String, level: Int?): CapabilityDto {
        if (level != null && level !in 1..4) throw BadRequestException("level 只能是 1–4 或不填")
        val member = memberService.current()
        val memberId = member.id!!
        val def = definitions.findByWorkspaceIdAndName(workspaceService.required().id!!, attribute)
            ?: throw NotFoundException("词表中没有属性:$attribute(先建属性)")
        val defId = def.id!!
        val existing = capabilities.findByMemberIdAndAttributeDefinitionId(memberId, defId)
        val saved = try {
            capabilities.save(
                (existing ?: Capability(memberId = memberId, attributeDefinitionId = defId))
                    .copy(level = level),
            )
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("能力写入冲突")
        }
        return CapabilityDto(def.name, saved.level)
    }

    @Transactional
    fun delete(attribute: String) {
        val member = memberService.current()
        val def = definitions.findByWorkspaceIdAndName(workspaceService.required().id!!, attribute)
            ?: throw NotFoundException("词表中没有属性:$attribute")
        capabilities.findByMemberIdAndAttributeDefinitionId(member.id!!, def.id!!)
            ?.let { capabilities.delete(it) }
    }

    private fun definitionNames(): Map<Long, String> =
        definitions.findByWorkspaceId(workspaceService.required().id!!).associate { it.id!! to it.name }
}

data class UpsertCapabilityRequest(
    @field:NotBlank val attribute: String,
    val level: Int? = null,
)
