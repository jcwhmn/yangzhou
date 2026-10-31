package yangzhou.api.business.dependency

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemDependency
import yangzhou.persistence.repository.ItemDependencyRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.util.UUID

/**
 * V9-S3 item 依赖:本 item 依赖 depends_on_item;依赖未到终态 → ⛔ 被阻塞。
 * 仅标识+过滤,不强制流转(V9-Q6);增依赖时沿链查环。
 */
@Service
class DependencyService(
    private val dependencies: ItemDependencyRepository,
    private val items: ItemRepository,
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
) {
    data class DependencyDto(
        val dependencyItemId: UUID, // 行 object_id(删除用)
        val itemId: UUID, // 被依赖项 objectId
        val number: String,
        val title: String,
        val statusName: String,
        val final: Boolean,
    )

    data class DependencyListDto(
        val dependencies: List<DependencyDto>,
        val blocked: Boolean,
    )

    fun list(itemId: UUID): DependencyListDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val projectObjectId = item.projectObjectId
        val project = projects.findByObjectId(projectObjectId)
        val statusById = statuses.findByProjectObjectIdOrderByPosition(projectObjectId).associateBy { it.objectId }
        val deps =
            dependencies.findByItemObjectId(item.objectId!!).map { row ->
                val target = items.findByObjectId(row.dependsOnItemObjectId)
                val status = target?.let { statusById[it.statusObjectId] }
                DependencyDto(
                    dependencyItemId = row.objectId,
                    itemId = target?.objectId ?: UUID(0, 0),
                    number = target?.let { "${project?.key}-${it.number}" } ?: "?",
                    title = target?.title ?: "",
                    statusName = status?.name ?: "?",
                    final = status?.isFinal == true,
                )
            }
        return DependencyListDto(deps, blocked = deps.any { !it.final })
    }

    @Transactional
    fun add(
        itemId: UUID,
        dependsOnItemId: UUID,
    ): DependencyDto {
        if (itemId == dependsOnItemId) throw BadRequestException("item 不能依赖自己")
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val target = items.findByObjectId(dependsOnItemId) ?: throw BadRequestException("被依赖 item 不存在")
        if (target.projectObjectId != item.projectObjectId) throw BadRequestException("依赖仅限同项目 item")
        val itemObjectIdLong = item.objectId!!
        val targetIdLong = target.objectId!!
        if (dependencies.existsByItemObjectIdAndDependsOnItemObjectId(itemObjectIdLong, targetIdLong)) throw ConflictException("依赖已存在")
        if (reaches(itemObjectIdLong, targetIdLong)) throw BadRequestException("会产生依赖环")
        val saved = dependencies.save(ItemDependency(itemObjectId = itemObjectIdLong, dependsOnItemObjectId = targetIdLong))
        val project = projects.findByObjectId(item.projectObjectId)
        val status =
            statuses
                .findByProjectObjectIdOrderByPosition(item.projectObjectId)
                .firstOrNull { it.objectId == target.statusObjectId }
        return DependencyDto(
            dependencyItemId = saved.objectId,
            itemId = target.objectId,
            number = "${project?.key}-${target.number}",
            title = target.title,
            statusName = status?.name ?: "?",
            final = status?.isFinal == true,
        )
    }

    @Transactional
    fun remove(rowObjectId: UUID) {
        val row = dependencies.findByObjectId(rowObjectId) ?: throw NotFoundException("依赖不存在")
        dependencies.delete(row)
    }

    /** 项目内被阻塞 item 的内部 id 集合(看板列表用;依赖未终态即阻塞,终态自身不算阻塞他人)。 */
    fun blockedItemObjectIds(projectObjectId: UUID): Set<UUID> {
        val projectItems = items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectObjectId)
        val itemById = projectItems.associateBy { it.objectId }
        val finalByObjectId =
            statuses
                .findByProjectObjectIdOrderByPosition(projectObjectId)
                .associate { it.objectId to it.isFinal }
        return dependencies
            .findByItemObjectIdIn(projectItems.mapNotNull { it.objectId })
            .filter { row ->
                val target = itemById[row.dependsOnItemObjectId]
                target != null && finalByObjectId[target.statusObjectId] != true
            }.map { it.itemObjectId }
            .toSet()
    }

    private fun reaches(
        from: UUID,
        target: UUID,
    ): Boolean {
        var frontier = listOf(from)
        val seen = mutableSetOf<UUID>()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<UUID>()
            for (node in frontier) {
                if (node == target) return true
                if (!seen.add(node)) continue
                dependencies.findByDependsOnItemObjectId(node).forEach { next.add(it.itemObjectId) }
            }
            frontier = next
        }
        return false
    }
}

data class CreateDependencyRequest(
    val dependsOnItemId: UUID,
)
