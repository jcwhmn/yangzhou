package yangzhou.api.dependency

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
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
        val itemId: UUID,           // 被依赖项 objectId
        val number: String,
        val title: String,
        val statusName: String,
        val final: Boolean,
    )

    data class DependencyListDto(val dependencies: List<DependencyDto>, val blocked: Boolean)

    fun list(itemId: UUID): DependencyListDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val projectId = item.projectId
        val project = projects.findById(projectId).orElse(null)
        val statusById = statuses.findByProjectIdOrderByPosition(projectId).associateBy { it.objectId }
        val deps = dependencies.findByItemId(item.id!!).map { row ->
            val target = items.findById(row.dependsOnItemId).orElse(null)
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
    fun add(itemId: UUID, dependsOnItemId: UUID): DependencyDto {
        if (itemId == dependsOnItemId) throw BadRequestException("item 不能依赖自己")
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val target = items.findByObjectId(dependsOnItemId) ?: throw BadRequestException("被依赖 item 不存在")
        if (target.projectId != item.projectId) throw BadRequestException("依赖仅限同项目 item")
        val itemIdLong = item.id!!
        val targetIdLong = target.id!!
        if (dependencies.existsByItemIdAndDependsOnItemId(itemIdLong, targetIdLong)) throw ConflictException("依赖已存在")
        if (reaches(itemIdLong, targetIdLong)) throw BadRequestException("会产生依赖环")
        val saved = dependencies.save(ItemDependency(itemId = itemIdLong, dependsOnItemId = targetIdLong))
        val project = projects.findById(item.projectId).orElse(null)
        val status = statuses.findByProjectIdOrderByPosition(item.projectId)
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
    fun blockedItemIds(projectId: Long): Set<Long> {
        val projectItems = items.findByProjectIdOrderByNumber(projectId)
        val itemById = projectItems.associateBy { it.id }
        val finalByObjectId = statuses.findByProjectIdOrderByPosition(projectId)
            .associate { it.objectId to it.isFinal }
        return dependencies.findByItemIdIn(projectItems.mapNotNull { it.id })
            .filter { row ->
                val target = itemById[row.dependsOnItemId]
                target != null && finalByObjectId[target.statusObjectId] != true
            }
            .map { it.itemId }
            .toSet()
    }

    private fun reaches(from: Long, target: Long): Boolean {
        var frontier = listOf(from)
        val seen = mutableSetOf<Long>()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Long>()
            for (node in frontier) {
                if (node == target) return true
                if (!seen.add(node)) continue
                dependencies.findByDependsOnItemId(node).forEach { next.add(it.itemId) }
            }
            frontier = next
        }
        return false
    }
}

data class CreateDependencyRequest(
    val dependsOnItemId: UUID,
)

@RestController
@RequestMapping("/api")
class DependencyController(private val service: DependencyService) {

    @GetMapping("/items/{itemId}/dependencies")
    fun list(@PathVariable itemId: UUID): DependencyService.DependencyListDto = service.list(itemId)

    @PostMapping("/items/{itemId}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable itemId: UUID,
        @RequestBody request: CreateDependencyRequest,
    ): DependencyService.DependencyDto = service.add(itemId, request.dependsOnItemId)

    @DeleteMapping("/dependencies/{dependencyItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable dependencyItemId: UUID) = service.remove(dependencyItemId)
}
