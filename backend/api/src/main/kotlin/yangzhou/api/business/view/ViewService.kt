package yangzhou.api.business.view

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ProjectView
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectViewRepository
import java.time.Instant
import java.util.UUID

/** YPJ-47 视图配置(spec 0045):表格筛选/列配置按 用户×项目 私有保存;filters/columns 为 JSON 对象(TEXT 列存串)。 */
@Service
class ViewService(
    private val views: ProjectViewRepository,
    private val projects: ProjectRepository,
    private val memberService: MemberService,
    private val mapper: ObjectMapper,
) {
    data class ViewDto(val viewId: UUID, val name: String, val filters: JsonNode, val columns: JsonNode)

    data class CreateViewRequest(val name: String, val filters: JsonNode? = null, val columns: JsonNode? = null)

    data class UpdateViewRequest(val name: String? = null, val filters: JsonNode? = null, val columns: JsonNode? = null)

    @Transactional(readOnly = true)
    fun list(key: String): List<ViewDto> {
        val (projectId, memberId) = requireContext(key)
        return views.findByProjectIdAndCreatorIdOrderByCreatedAt(projectId, memberId).map { toDto(it) }
    }

    @Transactional
    fun create(key: String, request: CreateViewRequest): ViewDto {
        val (projectId, memberId) = requireContext(key)
        val name = request.name.trim()
        if (name.isEmpty()) throw BadRequestException("视图名不可为空")
        val filters = validated(request.filters, "filters")
        val columns = validated(request.columns, "columns")
        if (views.existsByProjectIdAndCreatorIdAndName(projectId, memberId, name)) {
            throw ConflictException("同名视图已存在:$name")
        }
        val saved = views.save(
            ProjectView(
                projectId = projectId,
                creatorId = memberId,
                name = name,
                filters = filters.toString(),
                columns = columns.toString(),
            ),
        )
        return toDto(saved)
    }

    @Transactional
    fun update(key: String, viewId: UUID, request: UpdateViewRequest): ViewDto {
        val (projectId, memberId) = requireContext(key)
        // 只能改自己的:非本人/不存在一律 404(不泄露存在性)
        val view = views.findByObjectIdAndCreatorId(viewId, memberId) ?: throw NotFoundException("视图不存在")
        if (view.projectId != projectId) throw NotFoundException("视图不存在")
        var cur = view
        request.name?.let {
            val name = it.trim()
            if (name.isEmpty()) throw BadRequestException("视图名不可为空")
            if (name != view.name && views.existsByProjectIdAndCreatorIdAndName(projectId, memberId, name)) {
                throw ConflictException("同名视图已存在:$name")
            }
            cur = cur.copy(name = name)
        }
        request.filters?.let { cur = cur.copy(filters = validated(it, "filters").toString()) }
        request.columns?.let { cur = cur.copy(columns = validated(it, "columns").toString()) }
        cur = cur.copy(updatedAt = Instant.now())
        return toDto(views.save(cur))
    }

    @Transactional
    fun delete(key: String, viewId: UUID) {
        val (projectId, memberId) = requireContext(key)
        val view = views.findByObjectIdAndCreatorId(viewId, memberId) ?: throw NotFoundException("视图不存在")
        if (view.projectId != projectId) throw NotFoundException("视图不存在")
        views.delete(view)
    }

    /** 项目存在即可(仓内惯例:项目级端点不做成员校验,单用户产品形态);视图私有性由 creatorId 保证。 */
    private fun requireContext(key: String): Pair<Long, Long> {
        val p = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val me = memberService.current()
        val pid = requireNotNull(p.id) { "no id" }
        val mid = requireNotNull(me.id) { "member no id" }
        return pid to mid
    }

    /** 校验为 JSON 对象且 ≤4KB;null 返回空对象。 */
    private fun validated(node: JsonNode?, field: String): JsonNode {
        val n = node ?: mapper.readTree("{}")
        if (!n.isObject) throw BadRequestException("$field 须为 JSON 对象")
        if (n.toString().toByteArray(Charsets.UTF_8).size > 4096) throw BadRequestException("$field 超过 4KB 上限")
        return n
    }

    private fun toDto(v: ProjectView) = ViewDto(v.objectId, v.name, mapper.readTree(v.filters), mapper.readTree(v.columns))
}
