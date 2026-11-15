package yangzhou.api.business.release

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.Release
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ReleaseRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class ReleaseService(
    private val projects: ProjectRepository,
    private val releases: ReleaseRepository,
    private val leaderGuard: LeaderGuard,
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

    private fun Release.toDto() = ReleaseDto(
        releaseId = objectId,
        name = name,
        version = version,
        stage = stage,
        status = status,
        targetDate = targetDate?.toString(),
        releasedDate = releasedDate?.toString(),
        createdAt = createdAt.toString(),
    )

    /** planned 在前(targetDate asc nulls last,创建序),released 在后(按发布日倒序——最新发布在最上)。 */
    fun list(key: String): List<ReleaseDto> {
        // released 取负 epochDay = 发布日倒序;planned 取正 epochDay = 目标日升序;null 都垫底
        fun sortKey(r: Release): Long = when {
            r.status == "released" -> -(r.releasedDate?.toEpochDay() ?: Long.MAX_VALUE)
            else -> r.targetDate?.toEpochDay() ?: Long.MAX_VALUE
        }
        return releases.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!)
            .sortedWith(compareBy<Release> { if (it.status == "released") 1 else 0 }.thenBy { sortKey(it) }.thenBy { it.objectId })
            .map { it.toDto() }
    }

    @Transactional
    fun create(
        key: String,
        request: CreateReleaseRequest,
    ): ReleaseDto {
        leaderGuard.requireLeader(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val stage = validateStage(request.stage)
        val saved =
            releases.save(
                Release(
                    projectObjectId = project(key).objectId!!,
                    name = name,
                    version = request.version?.trim()?.takeIf { it.isNotEmpty() },
                    stage = stage,
                    targetDate = parseDate(request.targetDate, "targetDate"),
                    // US-R5 另票:事后补建(直接 released)不在本票,create 一律 planned
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
        leaderGuard.requireLeader(key)
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
        leaderGuard.requireLeader(key)
        val r = find(key, releaseId)
        if (r.status == "released") throw ConflictException("终态 release 不可删除(历史保留)")
        // 「有成员拒删」守卫随 US-R3(挂接叉表)落地;本票尚无成员机制,planned 可直接删
        releases.delete(r)
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
