package yangzhou.api.business.grouptype

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GroupTypeController(private val service: GroupTypeService) {

    @GetMapping("/projects/{key}/group-types")
    fun listTypes(@PathVariable key: String): List<GroupTypeService.GroupTypeDto> = service.listTypes(key)

    @PostMapping("/projects/{key}/group-types")
    @ResponseStatus(HttpStatus.CREATED)
    fun createType(
        @PathVariable key: String,
        @RequestBody request: GroupTypeService.CreateGroupTypeRequest,
    ): GroupTypeService.GroupTypeDto = service.createType(key, request)

    @PatchMapping("/projects/{key}/group-types/{typeKey}")
    fun updateType(
        @PathVariable key: String,
        @PathVariable typeKey: String,
        @RequestBody request: GroupTypeService.UpdateGroupTypeRequest,
    ): GroupTypeService.GroupTypeDto = service.updateType(key, typeKey, request)

    @DeleteMapping("/projects/{key}/group-types/{typeKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteType(@PathVariable key: String, @PathVariable typeKey: String) = service.deleteType(key, typeKey)

    @GetMapping("/projects/{key}/groups")
    fun listGroups(
        @PathVariable key: String,
        @RequestParam type: String,
    ): List<GroupTypeService.GroupDto> = service.listGroups(key, type)

    @PostMapping("/projects/{key}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(
        @PathVariable key: String,
        @RequestBody request: GroupTypeService.CreateGroupRequest,
    ): GroupTypeService.GroupDto = service.createGroup(key, request)

    @PatchMapping("/projects/{key}/groups/{groupId}")
    fun updateGroup(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
        @RequestBody request: GroupTypeService.UpdateGroupRequest,
    ): GroupTypeService.GroupDto = service.updateGroup(key, groupId, request)

    @DeleteMapping("/projects/{key}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGroup(@PathVariable key: String, @PathVariable groupId: UUID) = service.deleteGroup(key, groupId)
}
