package yangzhou.api.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchLikePatternUnitTest {
    @Test
    fun `通配符与反斜杠按字面转义`() {
        assertEquals("%100\\%%", SearchService.likePattern("100%"))
        assertEquals("%a\\\\b%", SearchService.likePattern("a\\b"))
        assertEquals("%f\\_o%", SearchService.likePattern("f_o"))
        assertEquals("%计划%", SearchService.likePattern("计划"))
    }
}
