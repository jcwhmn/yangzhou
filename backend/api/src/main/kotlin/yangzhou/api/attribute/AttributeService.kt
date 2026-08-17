package yangzhou.api.attribute

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.AttributeDefinitionEntity
import yangzhou.persistence.repository.AttributeDefinitionRepository
import java.util.UUID

/** 属性词表(kind × leveled,workspace 级;ADR-0003)。 */
@Service
class AttributeService(
    private val definitions: AttributeDefinitionRepository,
    private val workspaceService: WorkspaceService,
) {

    data class AttributeDto(
        val attributeId: UUID,
        val name: String,
        val kind: String,
        val leveled: Boolean,
    )

    @Transactional
    fun create(name: String, kind: String, leveled: Boolean): AttributeDto {
        val workspaceId = workspaceService.required().id!!
        validateKind(kind)
        if (definitions.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw ConflictException("属性已存在:$name")
        }
        val saved = definitions.save(
            AttributeDefinitionEntity(workspaceId = workspaceId, name = name, kind = kind, leveled = leveled),
        )
        return saved.toDto()
    }

    fun list(): List<AttributeDto> =
        definitions.findByWorkspaceId(workspaceService.required().id!!).map { it.toDto() }

    @Transactional
    fun update(attributeId: UUID, kind: String?, leveled: Boolean?): AttributeDto {
        val workspaceId = workspaceService.required().id!!
        val entity = definitions.findByWorkspaceIdAndObjectId(workspaceId, attributeId)
            ?: throw NotFoundException("属性不存在")
        kind?.let { validateKind(it) }
        // leveled 关→开:已有等级数据休眠待唤醒;开→关同理,不删数据(AGENTS 硬规则)
        val updated = entity.copy(
            kind = kind ?: entity.kind,
            leveled = leveled ?: entity.leveled,
        )
        return try {
            definitions.save(updated).toDto()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("属性更新冲突")
        }
    }

    @Transactional
    fun delete(attributeId: UUID) {
        val workspaceId = workspaceService.required().id!!
        val entity = definitions.findByWorkspaceIdAndObjectId(workspaceId, attributeId)
            ?: throw NotFoundException("属性不存在")
        try {
            definitions.delete(entity)
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("属性仍被能力或需求引用,无法删除")
        }
    }

    private fun validateKind(kind: String) {
        if (kind !in setOf("skill", "label")) {
            throw BadRequestException("kind 只能是 skill 或 label:$kind")
        }
    }

    private fun AttributeDefinitionEntity.toDto() =
        AttributeDto(objectId, name, kind, leveled)
}

data class CreateAttributeRequest(
    @field:NotBlank val name: String,
    val kind: String = "skill",
    val leveled: Boolean = false,
)

data class UpdateAttributeRequest(val kind: String? = null, val leveled: Boolean? = null)
