package yangzhou.api.favorite

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class FavoriteController(private val service: FavoriteService) {

    @GetMapping("/favorites")
    fun list(): List<FavoriteService.FavoriteDto> = service.list()

    @PutMapping("/projects/{key}/favorite")
    fun add(@PathVariable key: String): FavoriteService.FavoriteDto = service.add(key)

    @DeleteMapping("/projects/{key}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable key: String) = service.remove(key)
}
