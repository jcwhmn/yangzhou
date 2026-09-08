package yangzhou.api.search

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class SearchController(private val service: SearchService) {

    @GetMapping("/search")
    fun search(@RequestParam q: String): List<SearchService.SearchResult> = service.search(q)
}
