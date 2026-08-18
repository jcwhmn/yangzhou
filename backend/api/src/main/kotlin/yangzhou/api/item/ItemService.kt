package yangzhou.api.item

import jakarta.validation.constraints.NotBlank
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Requirement
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.ItemNumberRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import yangzhou.persistence.repository.StatusRepository
import java.util.UUID

/** Item:项目内递增编号(key-N)、type 属性、parentId 同质树、自由迁移(ADR-0002)。 */
@Service
class ItemService(
    private val projects: ProjectRepository,
    private val itemRepo: ItemRepository,
    private val statuses: StatusRepository,
    private val requirementRepo: RequirementRepository,
    private val definitions: AttributeDefinitionRepository,
    private val numbering: ItemNumberRepository,
    private val jdbc: JdbcOperations,
) {

    data class RequirementDto(val attribute: String, val minLevel: Int?)
    data class ItemDto(
        val itemId: UUID,
        val number: String,
        val title: String,
        val description: String?,
        val type: String,
        val status: String,
        val parentItemId: UUID?,
        val requirements: List<RequirementDto>,
    )

    @Transactional
    fun create(
        projectKey: String,
        title: String,
        description: String?,
        type: String,
        parentItemId: UUID?,
        statusItemId: UUID?,
        requirements: List<RequirementDto>,
    ): ItemDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        if (type !in setOf("task", "bug", "goal", "story")) throw BadRequestException("type 非法:$type")
        val projectId = project.id!!

        val number = numbering.nextNumber(projectId) // 原子取号,行锁防并发重号

        val status = when {
            statusItemId != null -> statuses.findByProjectIdAndObjectId(projectId, statusItemId)
                ?: throw BadRequestException("状态不属于该项目")
            else -> statuses.findByProjectIdOrderByPosition(projectId).firstOrNull()
                ?: throw ConflictException("项目无状态")
        }

        parentItemId?.let {
            val parent = itemRepo.findByObjectId(it) ?: throw BadRequestException("父 item 不存在")
            if (parent.projectId != project.id) throw BadRequestException("父 item 不属于该项目")
        }

        val item = itemRepo.save(
            Item(
                projectId = projectId,
                number = number,
                title = title,
                description = description,
                type = type,
                parentObjectId = parentItemId,
                statusObjectId = status.objectId,
            ),
        )
        val itemId = item.id!!
        resolveRequirements(project.workspaceId, requirements).forEach { (defId, minLevel) ->
            requirementRepo.save(Requirement(itemId = itemId, attributeDefinitionId = defId, minLevel = minLevel))
        }
        return get(item.objectId)
    }

    fun list(projectKey: String): List<ItemDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectId = project.id!!
        val statusNames = statuses.findByProjectIdOrderByPosition(projectId).associate { it.objectId to it.name }
        val attrNames = definitions.findByWorkspaceId(project.workspaceId).associate { it.id!! to it.name }
        val projectItems = itemRepo.findByProjectIdOrderByNumber(projectId)
        val reqs = requirementRepo.findByItemIdIn(projectItems.mapNotNull { it.id })
        return projectItems.map { item ->
            ItemDto(
                itemId = item.objectId,
                number = "${project.key}-${item.number}",
                title = item.title,
                description = item.description,
                type = item.type,
                status = statusNames[item.statusObjectId] ?: "?",
                parentItemId = item.parentObjectId,
                requirements = reqs.filter { it.itemId == item.id }.map {
                    RequirementDto(attrNames[it.attributeDefinitionId] ?: "?", it.minLevel)
                },
            )
        }
    }

    fun get(itemId: UUID): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projects.findAll().firstOrNull { it.id == item.projectId }
            ?: throw NotFoundException("项目不存在")
        return list(project.key).first { it.itemId == itemId }
    }

    @Transactional
    fun update(
        itemId: UUID,
        title: String?,
        description: String?,
        statusItemId: UUID?,
        parentItemId: UUID?,
    ): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projects.findAll().firstOrNull { it.id == item.projectId }
            ?: throw NotFoundException("项目不存在")
        var current = item

        if (statusItemId != null) {
            val status = statuses.findByProjectIdAndObjectId(project.id ?: error("no id"), statusItemId)
                ?: throw BadRequestException("状态不属于该项目")
            current = itemRepo.save(current.copy(statusObjectId = status.objectId)) // V1 自由迁移
        }

        if (parentItemId != null) {
            val parent = itemRepo.findByObjectId(parentItemId) ?: throw BadRequestException("父 item 不存在")
            if (parent.projectId != project.id) throw BadRequestException("父 item 不属于该项目")
            ensureNoCycle(current, parent)
            current = itemRepo.save(current.copy(parentObjectId = parent.objectId))
        }

        if (title != null || description != null) {
            current = itemRepo.save(current.copy(title = title ?: current.title, description = description ?: current.description))
        }
        return get(current.objectId)
    }

    // ---------- 内部 ----------

    /** 防环:沿新父链上行,遇到自身即拒绝(领域规则 2)。 */
    private fun ensureNoCycle(item: Item, newParent: Item) {
        var cursor: UUID? = newParent.objectId
        var hops = 0
        while (cursor != null) {
            if (cursor == item.objectId) throw ConflictException("不能把 item 挂到自己的后代下")
            cursor = jdbc.query(
                "select parent_object_id from item where object_id = ?",
                { rs, _ -> rs.getObject(1) as? UUID },
                cursor,
            ).firstOrNull()
            if (++hops > 10_000) throw ConflictException("item 树异常,拒绝操作") // ponytail: 深度上限兜底
        }
    }

    private fun resolveRequirements(
        workspaceId: Long,
        requirements: List<RequirementDto>,
    ): List<Pair<Long, Int?>> {
        val defs = definitions.findByWorkspaceId(workspaceId)
        return requirements
            .groupBy { it.attribute }
            .map { (name, list) ->
                val def = defs.firstOrNull { it.name == name }
                    ?: throw BadRequestException("词表中没有属性:$name(先建属性)")
                def.id!! to list.first().minLevel
            }
    }
}

data class CreateItemRequest(
    @field:NotBlank val title: String,
    val description: String? = null,
    val type: String = "task",
    val parentItemId: UUID? = null,
    val statusItemId: UUID? = null,
    val requirements: List<RequirementInput> = emptyList(),
) {
    data class RequirementInput(val attribute: String, val minLevel: Int? = null)
}

data class UpdateItemRequest(
    val title: String? = null,
    val description: String? = null,
    val statusItemId: UUID? = null,
    val parentItemId: UUID? = null,
)
