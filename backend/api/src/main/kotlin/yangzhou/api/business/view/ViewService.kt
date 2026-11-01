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
    data class ViewDto(
        val viewId: UUID,
        val name: String,
        val filters: JsonNode,
        val columns: JsonNode,
        val sort: JsonNode? = null,
        val query: String? = null,
        val colOrder: JsonNode? = null,
    )

    data class CreateViewRequest(
        val name: String,
        val filters: JsonNode? = null,
        val columns: JsonNode? = null,
        val sort: JsonNode? = null,
        val query: String? = null,
        val colOrder: JsonNode? = null,
    )

    data class UpdateViewRequest(
        val name: String? = null,
        val filters: JsonNode? = null,
        val columns: JsonNode? = null,
        val sort: JsonNode? = null,
        val query: String? = null,
        val colOrder: JsonNode? = null,
    )

    @Transactional(readOnly = true)
    fun list(key: String): List<ViewDto> {
        val (projectObjectId, memberObjectId) = requireContext(key)
        return views.findByProjectObjectIdAndCreatorObjectIdOrderByCreatedAt(projectObjectId, memberObjectId).map { toDto(it) }
    }

    @Transactional
    fun create(
        key: String,
        request: CreateViewRequest,
    ): ViewDto {
        val (projectObjectId, memberObjectId) = requireContext(key)
        val name = request.name.trim()
        if (name.isEmpty()) throw BadRequestException("视图名不可为空")
        val filters = validated(request.filters, "filters")
        val columns = validated(request.columns, "columns")
        if (views.existsByProjectObjectIdAndCreatorObjectIdAndName(projectObjectId, memberObjectId, name)) {
            throw ConflictException("同名视图已存在:$name")
        }
        val saved =
            views.save(
                ProjectView(
                    projectObjectId = projectObjectId,
                    creatorObjectId = memberObjectId,
                    name = name,
                    filters = filters.toString(),
                    columns = columns.toString(),
                    sort = rawJson(request.sort, "sort"),
                    query = cappedQuery(request.query),
                    colOrder = rawJson(request.colOrder, "colOrder"),
                ),
            )
        return toDto(saved)
    }

    @Transactional
    fun update(
        key: String,
        viewId: UUID,
        request: UpdateViewRequest,
    ): ViewDto {
        val (projectObjectId, memberObjectId) = requireContext(key)
        // 只能改自己的:非本人/不存在一律 404(不泄露存在性)
        val view = views.findByObjectIdAndCreatorObjectId(viewId, memberObjectId) ?: throw NotFoundException("视图不存在")
        if (view.projectObjectId != projectObjectId) throw NotFoundException("视图不存在")
        var cur = view
        request.name?.let {
            val name = it.trim()
            if (name.isEmpty()) throw BadRequestException("视图名不可为空")
            if (name != view.name && views.existsByProjectObjectIdAndCreatorObjectIdAndName(projectObjectId, memberObjectId, name)) {
                throw ConflictException("同名视图已存在:$name")
            }
            cur = cur.copy(name = name)
        }
        request.filters?.let { cur = cur.copy(filters = validated(it, "filters").toString()) }
        request.columns?.let { cur = cur.copy(columns = validated(it, "columns").toString()) }
        request.sort?.let { cur = cur.copy(sort = rawJson(it, "sort")) }
        request.query?.let { cur = cur.copy(query = cappedQuery(it)) }
        request.colOrder?.let { cur = cur.copy(colOrder = rawJson(it, "colOrder")) }
        cur = cur.copy(updatedAt = Instant.now())
        return toDto(views.save(cur))
    }

    @Transactional
    fun delete(
        key: String,
        viewId: UUID,
    ) {
        val (projectObjectId, memberObjectId) = requireContext(key)
        val view = views.findByObjectIdAndCreatorObjectId(viewId, memberObjectId) ?: throw NotFoundException("视图不存在")
        if (view.projectObjectId != projectObjectId) throw NotFoundException("视图不存在")
        views.delete(view)
    }

    /** 项目存在即可(仓内惯例:项目级端点不做成员校验,单用户产品形态);视图私有性由 creatorObjectId 保证。 */
    private fun requireContext(key: String): Pair<UUID, UUID> {
        val p = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val me = memberService.current()
        val pid = requireNotNull(p.objectId) { "no id" }
        val mid = requireNotNull(me.objectId) { "member no id" }
        return pid to mid
    }

    /** 校验为 JSON 对象且 ≤4KB;null 返回空对象。 */
    /** sort/query 透传不解释(spec 0091 §5):无形状校验,仅 4KB 上限;null 存 NULL。 */
    private fun rawJson(
        node: JsonNode?,
        field: String,
    ): String? {
        if (node == null || node.isNull) return null
        if (node.toString().toByteArray(Charsets.UTF_8).size > 4096) throw BadRequestException("$field 超出 4KB 限制")
        return node.toString()
    }

    private fun cappedQuery(q: String?): String? {
        if (q == null) return null
        if (q.toByteArray(Charsets.UTF_8).size > 4096) throw BadRequestException("query 超出 4KB 限制")
        return q
    }

    private fun validated(
        node: JsonNode?,
        field: String,
    ): JsonNode {
        val n = node ?: mapper.readTree("{}")
        if (!n.isObject) throw BadRequestException("$field 须为 JSON 对象")
        if (n.toString().toByteArray(Charsets.UTF_8).size > 4096) throw BadRequestException("$field 超过 4KB 上限")
        return n
    }

    private fun toDto(v: ProjectView): ViewDto = ViewDto(
        v.objectId,
        v.name,
        mapper.readTree(v.filters),
        mapper.readTree(v.columns),
        v.sort?.let { mapper.readTree(it) },
        v.query,
        v.colOrder?.let { mapper.readTree(it) },
    )
}
