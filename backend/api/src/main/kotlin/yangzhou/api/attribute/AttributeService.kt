package yangzhou.api.attribute

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.AttributeDefinition
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
        val parentId: Long? = null,
        val categoryName: String? = null,
    )

    @Transactional
    fun create(
        name: String,
        kind: String,
        leveled: Boolean,
        parentObjectId: UUID?,
    ): AttributeDto {
        val workspaceId = workspaceService.required().id!!
        validateKind(kind)
        if (definitions.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw ConflictException("属性已存在:$name")
        }
        // V10-S1 两层固定:category 为分类根(parent 恒空);叶子可挂分类,空 = 未分类
        var resolvedParent: Long? = null
        if (parentObjectId != null) {
            if (kind == "category") throw BadRequestException("分类不能再挂父分类")
            val parent = definitions.findByWorkspaceIdAndObjectId(workspaceId, parentObjectId)
                ?: throw NotFoundException("分类不存在")
            if (parent.kind != "category") throw BadRequestException("parent 必须是分类(kind=category)")
            resolvedParent = parent.id
        }
        val saved = definitions.save(
            AttributeDefinition(
                workspaceId = workspaceId, name = name, kind = kind, leveled = leveled,
                parentId = resolvedParent,
            ),
        )
        return saved.toDto()
    }


    @Transactional
    fun update(
        attributeId: UUID,
        kind: String?,
        leveled: Boolean?,
        parentObjectId: UUID?,
        unassign: Boolean,
    ): AttributeDto {
        val workspaceId = workspaceService.required().id!!
        val entity = definitions.findByWorkspaceIdAndObjectId(workspaceId, attributeId)
            ?: throw NotFoundException("属性不存在")
        if (entity.kind == "category" && kind != null && kind != "category") {
            throw BadRequestException("分类不能变更类型")
        }
        kind?.let { validateKind(it) }
        // V10-S1:叶子移动分类(unassign=true → 未分类);category 不可移动
        var newParent = entity.parentId
        if (entity.kind != "category") {
            if (unassign) newParent = null
            else if (parentObjectId != null) {
                val parent = definitions.findByWorkspaceIdAndObjectId(workspaceId, parentObjectId)
                    ?: throw NotFoundException("分类不存在")
                if (parent.kind != "category") throw BadRequestException("parent 必须是分类")
                newParent = parent.id
            }
        }
        // leveled 关→开:已有等级数据休眠待唤醒;开→关同理,不删数据(AGENTS 硬规则)
        val updated = entity.copy(
            kind = kind ?: entity.kind,
            leveled = leveled ?: entity.leveled,
            parentId = newParent,
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
        if (entity.kind == "category" &&
            definitions.findByWorkspaceId(workspaceId).any { it.parentId == entity.id }
        ) {
            throw ConflictException("分类下仍有条目,先移出再删除")
        }
        try {
            definitions.delete(entity)
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("属性仍被能力或需求引用,无法删除")
        }
    }

    private fun validateKind(kind: String) {
        if (kind !in setOf("skill", "label", "category")) {
            throw BadRequestException("kind 只能是 skill、label 或 category:$kind")
        }
    }

    private fun AttributeDefinition.toDto(categoryName: String? = null) =
        AttributeDto(objectId, name, kind, leveled, parentId, categoryName)

    /** list:叶子带分类名(未分类 = null)。 */
    fun list(): List<AttributeDto> {
        val all = definitions.findByWorkspaceId(workspaceService.required().id!!)
        val catNames = all.associateBy { it.id }
        return all.map { it.toDto(catNames[it.parentId]?.name) }
    }
}

data class CreateAttributeRequest(
    @field:NotBlank val name: String,
    val kind: String = "skill",
    val leveled: Boolean = false,
    val parentId: UUID? = null,
)

data class UpdateAttributeRequest(
    val kind: String? = null,
    val leveled: Boolean? = null,
    val parentObjectId: UUID? = null,
    val unassign: Boolean = false,
)
