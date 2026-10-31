package yangzhou.api.business.search

import org.springframework.stereotype.Service
import yangzhou.persistence.repository.SearchRepository
import java.util.UUID

/** 全局搜索:跨项目按标题/描述/编号匹配 item(V1:ILIKE;全文引擎 defer)。 */
@Service
class SearchService(
    private val searchRepo: SearchRepository,
) {
    data class SearchResult(
        val itemId: UUID,
        val projectKey: String,
        val number: String,
        val title: String,
        val status: String,
    )

    fun search(query: String): List<SearchResult> = searchRepo.search(likePattern(query)).map {
        SearchResult(it.itemId, it.projectKey, it.number, it.title, it.status ?: "")
    }

    companion object {
        /** D7(spec 0052):用户输入中的 ILIKE 通配符与转义符按字面匹配。 */
        fun likePattern(query: String): String = "%" +
            query
                .trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%"
    }
}
