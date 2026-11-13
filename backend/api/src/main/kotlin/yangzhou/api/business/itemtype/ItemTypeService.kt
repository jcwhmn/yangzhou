package yangzhou.api.business.itemtype

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.ItemType
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTypeRepository
import yangzhou.persistence.repository.ProjectRepository

/** YPJ-177(spec 0123):item type 项目级运行时定义——镜像 ItemGroupService 类型 CRUD。
 * 值语义:story=主线单元/task=插入小活/bug=缺陷/goal=树根容器(判据=计划来源,详见 spec ①)。 */
@Service
class ItemTypeService(
    private val projects: ProjectRepository,
    private val itemTypes: ItemTypeRepository,
    private val items: ItemRepository,
    private val leaderGuard: LeaderGuard,
) {
    data class ItemTypeDto(
        val typeKey: String,
        val name: String,
        val builtin: Boolean,
    )

    data class CreateItemTypeRequest(
        val typeKey: String?,
        val name: String?,
    )

    data class UpdateItemTypeRequest(
        val name: String?,
    )

    private fun project(key: String) = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun ItemType.toDto() = ItemTypeDto(typeKey, name, builtin)

    fun list(key: String): List<ItemTypeDto> = itemTypes.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).map { it.toDto() }

    /** 镜像 group typeKey 约束([a-z][a-z0-9-]{1,19});name 承载显示文案。 */
    @Transactional
    fun create(
        key: String,
        request: CreateItemTypeRequest,
    ): ItemTypeDto {
        leaderGuard.requireLeader(key)
        val typeKey =
            request.typeKey?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw BadRequestException("typeKey 必填")
        if (!Regex("[a-z][a-z0-9-]{1,19}").matches(typeKey)) {
            throw BadRequestException("typeKey 需匹配 [a-z][a-z0-9-]{1,19}")
        }
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val pid = project(key).objectId!!
        if (itemTypes.existsByProjectObjectIdAndTypeKey(pid, typeKey)) {
            throw ConflictException("item 类型已存在:$typeKey")
        }
        return itemTypes.save(ItemType(projectObjectId = pid, typeKey = typeKey, name = name)).toDto()
    }

    /** typeKey 不可变(标识符),PATCH 仅 name。 */
    @Transactional
    fun update(
        key: String,
        typeKey: String,
        request: UpdateItemTypeRequest,
    ): ItemTypeDto {
        leaderGuard.requireLeader(key)
        val pid = project(key).objectId!!
        val t =
            itemTypes.findByProjectObjectIdAndTypeKey(pid, typeKey)
                ?: throw NotFoundException("item 类型不存在:$typeKey")
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        return itemTypes.save(t.copy(name = name)).toDto()
    }

    /** 镜像 spec 0114 拍板:builtin 不可删;类型在用(有 item 引用)409 先迁移;空即可删。 */
    @Transactional
    fun delete(
        key: String,
        typeKey: String,
    ) {
        leaderGuard.requireLeader(key)
        val pid = project(key).objectId!!
        val t =
            itemTypes.findByProjectObjectIdAndTypeKey(pid, typeKey)
                ?: throw NotFoundException("item 类型不存在:$typeKey")
        if (t.builtin) throw ConflictException("内建类型不可删除:$typeKey")
        val inUse = items.countByProjectObjectIdAndType(pid, typeKey)
        if (inUse > 0) throw ConflictException("类型下仍有 $inUse 个 item,请先迁移:$typeKey")
        itemTypes.delete(t)
    }
}
