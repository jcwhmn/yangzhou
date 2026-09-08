package yangzhou.api.search

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.stereotype.Service
import java.util.UUID

/** 全局搜索:跨项目按标题/描述匹配 item(V1:ILIKE;全文引擎 defer)。 */
@Service
class SearchService(private val jdbc: JdbcOperations) {

    data class SearchResult(
        val itemId: UUID,
        val projectKey: String,
        val number: String,
        val title: String,
        val status: String,
    )

    fun search(query: String): List<SearchResult> {
        val pattern = "%${query.trim()}%"
        return jdbc.query(
            """
            select i.object_id, p.key, i.number, i.title, s.name
            from item i
            join project p on i.project_id = p.id
            left join status s on i.status_object_id = s.object_id
            where i.title ilike ? or i.description ilike ?
            order by p.key, i.number
            limit 50
            """.trimIndent(),
            { rs, _ ->
                SearchResult(
                    rs.getObject("object_id", UUID::class.java),
                    rs.getString("key"),
                    rs.getString("key") + "-" + rs.getInt("number"),
                    rs.getString("title"),
                    rs.getString("name"),
                )
            },
            pattern, pattern,
        )
    }
}
