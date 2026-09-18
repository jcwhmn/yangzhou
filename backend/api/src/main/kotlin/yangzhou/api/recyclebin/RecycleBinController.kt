package yangzhou.api.recyclebin

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.item.ItemService
import java.util.UUID

/** V9-S6 回收站:软删 item 的恢复与物理删。 */
@RestController
@RequestMapping("/api/recycle-bin")
class RecycleBinController(private val itemService: ItemService) {

    @GetMapping
    fun list(): List<ItemService.RecycleItemDto> = itemService.recycleBin()

    @PostMapping("/{itemId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restore(@PathVariable itemId: UUID) = itemService.restore(itemId)

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun purge(@PathVariable itemId: UUID) = itemService.purge(itemId)
}
