package yangzhou.api.business.grouptype

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.repository.ProjectRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ItemGroupType 运行时定义(PRD §6,spec 0056):项目级 group 类型 CRUD + 自定义类型的通用组 CRUD。
 * sprint/release 走专属端点(sprint/release 切片),通用端点对其只读封闭;
 * 自定义类型统一最小语义:name + 可选起止日期 + planned/active/completed,不参与指标/忙闲聚合(领域规则 5)。
 */
@Service
class GroupTypeService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val types: ItemGroupTypeRepository,
    private val groups: ItemGroupRepository,
    private val memberships: ItemGroupMemberRepository,
    private val memberHistory: ItemGroupMemberHistoryRepository,
) {

    data class GroupTypeDto(val typeKey: String, val name: String, val builtin: Boolean)
    data class CreateGroupTypeRequest(val typeKey: String, val name: String)
    data class UpdateGroupTypeRequest(val name: String)

    data class GroupDto(
        val groupId: UUID,
        val typeKey: String,
        val name: String,
        val status: String,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class CreateGroupRequest(
        val typeKey: String,
        val name: String,
        val status: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class UpdateGroupRequest(
        val name: String? = null,
        val status: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    private val groupStatusValues = setOf("planned", "active", "completed")
    private val builtinKeys = setOf("sprint", "release")

    private fun project(key: String) =
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun ItemGroupType.toDto() = GroupTypeDto(typeKey, name, builtin)

    private fun ItemGroup.toDto(typeKey: String) = GroupDto(
        groupId = objectId,
        typeKey = typeKey,
        name = name,
        status = status,
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
    )

    private fun customType(key: String, typeKey: String): ItemGroupType {
        val p = project(key)
        val t = types.findByProjectObjectIdAndTypeKey(p.objectId!!, typeKey)
            ?: throw NotFoundException("group 类型不存在:$typeKey")
        if (t.typeKey in builtinKeys) throw BadRequestException("sprint/release 请用专属端点")
        return t
    }

    private fun parseDate(text: String?, field: String): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("$field 格式应为 yyyy-MM-dd")
        }
    }

    // ---------- 类型 CRUD ----------

    fun listTypes(key: String): List<GroupTypeDto> =
        types.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).map { it.toDto() }

    @Transactional
    fun createType(key: String, request: CreateGroupTypeRequest): GroupTypeDto {
        leaderGuard.requireLeader(key)
        val typeKey = request.typeKey?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw BadRequestException("typeKey 必填")
        if (!Regex("[a-z][a-z0-9-]{1,19}").matches(typeKey)) {
            throw BadRequestException("typeKey 需匹配 [a-z][a-z0-9-]{1,19}")
        }
        if (typeKey in builtinKeys) throw BadRequestException("typeKey 与内建类型冲突:$typeKey")
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val pid = project(key).objectId!!
        if (types.existsByProjectObjectIdAndTypeKey(pid, typeKey)) {
            throw ConflictException("group 类型已存在:$typeKey")
        }
        return types.save(ItemGroupType(projectObjectId = pid, typeKey = typeKey, name = name)).toDto()
    }

    @Transactional
    fun updateType(key: String, typeKey: String, request: UpdateGroupTypeRequest): GroupTypeDto {
        leaderGuard.requireLeader(key)
        val t = types.findByProjectObjectIdAndTypeKey(project(key).objectId!!, typeKey)
            ?: throw NotFoundException("group 类型不存在:$typeKey")
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw BadRequestException("name 必填")
        return types.save(t.copy(name = name)).toDto()
    }

    /** builtin 不可删;类型下仍有组即删除会丢组,409 拒绝(先删组或改类型)。 */
    @Transactional
    fun deleteType(key: String, typeKey: String) {
        leaderGuard.requireLeader(key)
        val pid = project(key).objectId!!
        val t = types.findByProjectObjectIdAndTypeKey(pid, typeKey)
            ?: throw NotFoundException("group 类型不存在:$typeKey")
        if (t.builtin) throw ConflictException("内建类型不可删除:$typeKey")
        if (groups.findByProjectObjectIdOrderByCreatedAt(pid).any { it.type == typeKey }) {
            throw ConflictException("类型下仍有组,请先删除:$typeKey")
        }
        types.delete(t)
    }

    // ---------- 自定义类型的通用组 CRUD ----------

    fun listGroups(key: String, typeKey: String): List<GroupDto> {
        val t = customType(key, typeKey)
        return groups.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!)
            .filter { it.type == typeKey }
            .map { it.toDto(typeKey) }
    }

    @Transactional
    fun createGroup(key: String, request: CreateGroupRequest): GroupDto {
        leaderGuard.requireLeader(key)
        val typeKey = request.typeKey?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw BadRequestException("typeKey 必填")
        val t = customType(key, typeKey)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val status = request.status ?: "planned"
        if (status !in groupStatusValues) throw BadRequestException("status 只能是 planned/active/completed")
        val saved = groups.save(
            ItemGroup(
                projectObjectId = project(key).objectId!!,
                type = t.typeKey,
                name = name,
                status = status,
                startDate = parseDate(request.startDate, "startDate"),
                endDate = parseDate(request.endDate, "endDate"),
            ),
        )
        return saved.toDto(t.typeKey)
    }

    @Transactional
    fun updateGroup(key: String, groupId: UUID, request: UpdateGroupRequest): GroupDto {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) throw BadRequestException("name 不能为空")
        if (request.status != null && request.status !in groupStatusValues) {
            throw BadRequestException("status 只能是 planned/active/completed")
        }
        val updated = g.copy(
            name = name ?: g.name,
            status = request.status ?: g.status,
            startDate = when {
                request.startDate == null -> g.startDate
                request.startDate.isEmpty() -> null
                else -> parseDate(request.startDate, "startDate")
            },
            endDate = when {
                request.endDate == null -> g.endDate
                request.endDate.isEmpty() -> null
                else -> parseDate(request.endDate, "endDate")
            },
            updatedAt = Instant.now(),
        )
        return groups.save(updated).toDto(g.type)
    }

    /** 自定义组删除:membership 是展示性组织,级联删(含留痕行);release 的「成员即交付历史」语义不适用于自定义组。 */
    @Transactional
    fun deleteGroup(key: String, groupId: UUID) {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        val gid = g.objectId!!
        memberships.findByGroupObjectId(gid).forEach { m ->
            memberHistory.findByGroupObjectId(gid).filter { it.itemObjectId == m.itemObjectId }.forEach(memberHistory::delete)
            memberships.delete(m)
        }
        groups.delete(g)
    }

    private fun findGroup(key: String, groupId: UUID): ItemGroup {
        val p = project(key)
        val g = groups.findByProjectObjectIdAndObjectId(p.objectId!!, groupId) ?: throw NotFoundException("group 不存在")
        if (g.type in builtinKeys) throw BadRequestException("sprint/release 请用专属端点")
        return g
    }
}
