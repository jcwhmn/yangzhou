package yangzhou.api.business.itemtype

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** YPJ-177(spec 0123):/projects/{key}/item-types CRUD——镜像 group-types 形状。 */
@RestController
@RequestMapping("/api")
class ItemTypeController(
    private val service: ItemTypeService,
) {
    @GetMapping("/projects/{key}/item-types")
    fun list(
        @PathVariable key: String,
    ): List<ItemTypeService.ItemTypeDto> = service.list(key)

    @PostMapping("/projects/{key}/item-types")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @RequestBody request: ItemTypeService.CreateItemTypeRequest,
    ): ItemTypeService.ItemTypeDto = service.create(key, request)

    @PatchMapping("/projects/{key}/item-types/{typeKey}")
    fun update(
        @PathVariable key: String,
        @PathVariable typeKey: String,
        @RequestBody request: ItemTypeService.UpdateItemTypeRequest,
    ): ItemTypeService.ItemTypeDto = service.update(key, typeKey, request)

    @DeleteMapping("/projects/{key}/item-types/{typeKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable key: String,
        @PathVariable typeKey: String,
    ) = service.delete(key, typeKey)
}
