package yangzhou.api.business.release

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.ProjectGuard
import yangzhou.persistence.Release
import yangzhou.persistence.ReleaseItem
import yangzhou.persistence.ReleaseItemHistory
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ReleaseItemHistoryRepository
import yangzhou.persistence.repository.ReleaseItemRepository
import yangzhou.persistence.repository.ReleaseRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ReleaseMembershipDto(val releaseId: UUID? = null, val releaseName: String? = null)

data class SetReleaseMembershipRequest(val releaseId: UUID? = null)

@Service
class ReleaseService(
    private val projects: ProjectRepository,
    private val releases: ReleaseRepository,
    private val releaseItems: ReleaseItemRepository,
    private val releaseItemHistories: ReleaseItemHistoryRepository,
    private val members: MemberRepository,
    private val items: ItemRepository,
    private val statuses: StatusRepository,
    private val projectGuard: ProjectGuard,
    private val memberService: MemberService,
    private val clock: Clock,
) {
    data class ReleaseDto(
        val releaseId: UUID,
        val name: String,
        val version: String? = null,
        val stage: String? = null,
        val status: String,
        val targetDate: String? = null,
        /** 仅 released 终态有值(实际发布日)。 */
        val releasedDate: String? = null,
        val createdAt: String,
        /** US-R7(spec 0136):planned 且 targetDate 已过;计算不落库。 */
        val overdue: Boolean = false,
        /** US-R8(spec 0136)口径 1b:done=FINAL 状态,分母=挂接未软删;仅供参考非发布资格。 */
        val doneCount: Int = 0,
        val totalCount: Int = 0,
    )

    data class ReleaseItemHistoryDto(
        val itemObjectId: UUID,
        val itemName: String,
        val action: String,
        val actorMemberObjectId: UUID? = null,
        val actorName: String? = null,
        val createdAt: Instant,
    )

    val stages = setOf("alpha", "beta", "rc", "ga")

    private val transitions = mapOf("planned" to setOf("released"), "released" to emptySet())

    private fun project(key: String) = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun find(
        key: String,
        releaseId: UUID,
    ): Release {
        val p = project(key)
        return releases.findByProjectObjectIdAndObjectId(p.objectId!!, releaseId) ?: throw NotFoundException("release 不存在")
    }

    private fun today() = LocalDate.now(clock)

    private fun Release.toDto(
        overdue: Boolean = false,
        doneCount: Int = 0,
        totalCount: Int = 0,
    ) = ReleaseDto(
        releaseId = objectId,
        name = name,
        version = version,
        stage = stage,
        status = status,
        targetDate = targetDate?.toString(),
        releasedDate = releasedDate?.toString(),
        createdAt = createdAt.toString(),
        overdue = overdue,
        doneCount = doneCount,
        totalCount = totalCount,
    )

    /** planned 在前(targetDate asc nulls last,创建序),released 在后(按发布日倒序——最新发布在最上)。 */
    fun list(key: String): List<ReleaseDto> {
        val all = releases.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!)
        val today = today()
        // US-R8 口径 1b(spec 0136):done=FINAL,分母=挂接未软删;批量取避免 N+1
        val rowsByRelease =
            releaseItems.findByReleaseObjectIdIn(all.map { it.objectId })
                .groupBy { it.releaseObjectId }
        val itemMap =
            items.findAllByObjectIdIn(rowsByRelease.values.flatten().map { it.itemObjectId }.toSet())
                .filter { it.deletedAt == null }
                .associateBy { it.objectId }
        val statusMap =
            statuses.findAllByObjectIdIn(itemMap.values.map { it.statusObjectId }.toSet())
                .associateBy { it.objectId }

        fun completion(r: Release): Pair<Int, Int> {
            val valid = (rowsByRelease[r.objectId] ?: emptyList()).mapNotNull { itemMap[it.itemObjectId] }
            return valid.count { statusMap[it.statusObjectId]?.statusType == StatusType.FINAL } to valid.size
        }

        fun sortKey(r: Release): Long = when {
            r.status == "released" -> -(r.releasedDate?.toEpochDay() ?: Long.MAX_VALUE)
            else -> r.targetDate?.toEpochDay() ?: Long.MAX_VALUE
        }
        return all
            .sortedWith(compareBy<Release> { if (it.status == "released") 1 else 0 }.thenBy { sortKey(it) }.thenBy { it.objectId })
            .map {
                val (done, total) = completion(it)
                val td = it.targetDate
                it.toDto(
                    overdue = it.status == "planned" && td != null && td.isBefore(today),
                    doneCount = done,
                    totalCount = total,
                )
            }
    }

    @Transactional
    fun create(
        key: String,
        request: CreateReleaseRequest,
    ): ReleaseDto {
        projectGuard.requireEditor(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val stage = validateStage(request.stage)
        // US-R5(spec 0135):事后补建——status=released 直接落终态;其余值非法(缺省 planned)
        val createStatus =
            when (request.status ?: "planned") {
                "planned" -> "planned"
                "released" -> "released"
                else -> throw BadRequestException("status 只能是 planned/released")
            }
        val backfillDate = parseDate(request.releasedDate, "releasedDate")
        if (createStatus == "released" && backfillDate != null && backfillDate.isAfter(today())) {
            throw BadRequestException("补建的 releasedDate 不能晚于今天")
        }
        val saved =
            releases.save(
                Release(
                    projectObjectId = project(key).objectId!!,
                    name = name,
                    version = request.version?.trim()?.takeIf { it.isNotEmpty() },
                    stage = stage,
                    targetDate = parseDate(request.targetDate, "targetDate"),
                    status = createStatus,
                    releasedDate = if (createStatus == "released") (backfillDate ?: today()) else null,
                ),
            )
        return saved.toDto()
    }

    @Transactional
    fun update(
        key: String,
        releaseId: UUID,
        request: UpdateReleaseRequest,
    ): ReleaseDto {
        projectGuard.requireEditor(key)
        val r = find(key, releaseId)
        if (r.status == "released") throw ConflictException("终态 release 不可编辑(历史保留;补发内容请新建 release)")
        if (request.status != null && request.status !in transitions.keys) {
            throw BadRequestException("status 只能是 planned/released")
        }
        if (request.status != null && request.status != r.status && request.status !in transitions.getValue(r.status)) {
            throw ConflictException("状态不可从 ${r.status} 迁移到 ${request.status}")
        }
        val publishing = request.status == "released" && r.status != "released"
        val saved =
            releases.save(
                r.copy(
                    name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.name,
                    version = when {
                        request.version == null -> r.version
                        request.version.isEmpty() -> null
                        else -> request.version.trim()
                    },
                    stage = if (request.stage == null) r.stage else validateStage(request.stage),
                    status = request.status ?: r.status,
                    targetDate =
                    when {
                        request.targetDate == null -> r.targetDate
                        request.targetDate.isEmpty() -> null
                        else -> parseDate(request.targetDate, "targetDate")
                    },
                    releasedDate = if (publishing) (request.releasedDate?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "releasedDate") } ?: today()) else r.releasedDate,
                    updatedAt = Instant.now(clock),
                ),
            )
        return saved.toDto()
    }

    @Transactional
    fun delete(
        key: String,
        releaseId: UUID,
    ) {
        projectGuard.requireEditor(key)
        val r = find(key, releaseId)
        if (r.status == "released") throw ConflictException("终态 release 不可删除(历史保留)")
        // US-R3(spec 0133):有成员拒删(PRD §5.1「有成员才拒绝」)——先摘空再删
        if (releaseItems.findByReleaseObjectId(r.objectId).isNotEmpty()) {
            throw ConflictException("release 下仍有挂接 item,先摘除后再删除")
        }
        releases.delete(r)
    }

    /** 当前归属真相(spec 0133):live 挂接 = 指向存在的 release;历史行(released 后被换挂)永不删。 */
    fun getMembership(itemId: UUID): ReleaseMembershipDto = membershipDto(itemId)

    @Transactional
    fun setMembership(
        itemId: UUID,
        request: SetReleaseMembershipRequest,
    ): ReleaseMembershipDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        if (item.deletedAt != null) throw BadRequestException("item 已删除")
        val actor = memberService.current().objectId
        val current = liveRows(item.objectId!!)
        if (request.releaseId == null) {
            // 摘:只删 planned 行;released 历史行永不碰(挂接真相只看 planned,PRD §5.1 冻结由行不可动保证)
            current.filter { it.second.status == "planned" }.forEach {
                releaseItems.delete(it.first)
                recordHistory(it.second.objectId, item.objectId!!, "detached", actor)
            }
            return ReleaseMembershipDto()
        }
        val target = releases.findByObjectId(request.releaseId) ?: throw NotFoundException("release 不存在")
        if (target.projectObjectId != item.projectObjectId) throw NotFoundException("release 不存在")
        if (target.status == "released") throw ConflictException("终态 release 不可挂接(历史保留)")
        // 不变量(PRD §12.4):同 item 至多属一个未终态 release
        current.firstOrNull { it.second.objectId != target.objectId && it.second.status == "planned" }
            ?.let { throw ConflictException("item 已属未终态 release:${it.second.name}(先摘后再挂)") }
        if (current.any { it.second.objectId == target.objectId }) return membershipDto(itemId) // 幂等
        releaseItems.save(ReleaseItem(releaseObjectId = target.objectId, itemObjectId = item.objectId!!))
        recordHistory(target.objectId, item.objectId!!, "attached", actor)
        return ReleaseMembershipDto(target.objectId, target.name)
    }

    /** YPJ-202(spec 0135):US-R6 留痕——挂/摘成功各一行;actor 可空。 */
    private fun recordHistory(
        releaseObjectId: UUID,
        itemObjectId: UUID,
        action: String,
        actorMemberObjectId: UUID?,
    ) {
        releaseItemHistories.save(
            ReleaseItemHistory(
                releaseObjectId = releaseObjectId,
                itemObjectId = itemObjectId,
                action = action,
                actorMemberObjectId = actorMemberObjectId,
            ),
        )
    }

    /** US-R6:release 侧留痕查询(倒序);release 删除后仍可查(弱引用悬空行照返)。 */
    fun history(
        key: String,
        releaseId: UUID,
    ): List<ReleaseItemHistoryDto> {
        val p = project(key)
        val target = releases.findByObjectId(releaseId)
        if (target != null && target.projectObjectId != p.objectId) throw NotFoundException("release 不存在")
        val rows = releaseItemHistories.findByReleaseObjectIdOrderByCreatedAtDesc(releaseId)
        val itemMap = items.findByObjectIdIn(rows.map { it.itemObjectId }.toSet()).associateBy { it.objectId }
        val memberMap = members.findAllByObjectIdIn(rows.mapNotNull { it.actorMemberObjectId }.toSet()).associateBy { it.objectId }
        return rows.map { row ->
            val item = itemMap[row.itemObjectId]
            val actor = row.actorMemberObjectId?.let { memberMap[it] }
            ReleaseItemHistoryDto(
                itemObjectId = row.itemObjectId,
                itemName = item?.title ?: "(已删除 item)",
                action = row.action,
                actorMemberObjectId = row.actorMemberObjectId,
                actorName = actor?.displayName,
                createdAt = row.createdAt,
            )
        }
    }

    /** live 行 = (行, release)。 */
    private fun liveRows(itemObjectId: UUID): List<Pair<ReleaseItem, Release>> {
        val rows = releaseItems.findByItemObjectId(itemObjectId)
        if (rows.isEmpty()) return emptyList()
        val map = releases.findAllByObjectIdIn(rows.map { it.releaseObjectId }.toSet()).associateBy { it.objectId }
        return rows.mapNotNull { row -> map[row.releaseObjectId]?.let { row to it } }
    }

    private fun membershipDto(itemId: UUID): ReleaseMembershipDto {
        val current = liveRows(itemId).firstOrNull { it.second.status == "planned" }
        return current?.let { ReleaseMembershipDto(it.second.objectId, it.second.name) } ?: ReleaseMembershipDto()
    }

    private fun validateStage(text: String?): String? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s !in stages) throw BadRequestException("stage 只能是 alpha/beta/rc/ga")
        return s
    }

    private fun parseDate(
        text: String?,
        field: String,
    ): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("$field 格式应为 yyyy-MM-dd")
        }
    }
}
