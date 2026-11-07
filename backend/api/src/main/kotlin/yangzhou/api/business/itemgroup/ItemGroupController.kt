package yangzhou.api.business.itemgroup

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.business.item.ItemService
import java.util.UUID

/** 统一组端点(spec 0097 S2):/groups* + 动作 + group-membership;/sprints* 与 PUT /items/{id}/sprint 下线,无兼容层。 */
@RestController
@RequestMapping("/api")
class ItemGroupController(
    private val service: ItemGroupService,
) {
    // ---------- 类型 ----------

    @GetMapping("/projects/{key}/group-types")
    fun listTypes(
        @PathVariable key: String,
    ): List<ItemGroupService.GroupTypeDto> = service.listTypes(key)

    @PostMapping("/projects/{key}/group-types")
    @ResponseStatus(HttpStatus.CREATED)
    fun createType(
        @PathVariable key: String,
        @RequestBody request: ItemGroupService.CreateGroupTypeRequest,
    ): ItemGroupService.GroupTypeDto = service.createType(key, request)

    @PatchMapping("/projects/{key}/group-types/{typeKey}")
    fun updateType(
        @PathVariable key: String,
        @PathVariable typeKey: String,
        @RequestBody request: ItemGroupService.UpdateGroupTypeRequest,
    ): ItemGroupService.GroupTypeDto = service.updateType(key, typeKey, request)

    @DeleteMapping("/projects/{key}/group-types/{typeKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteType(
        @PathVariable key: String,
        @PathVariable typeKey: String,
    ) = service.deleteType(key, typeKey)

    @GetMapping("/projects/{key}/group-types/{typeKey}/workflow")
    fun workflow(
        @PathVariable key: String,
        @PathVariable typeKey: String,
    ): List<GroupWorkflowService.GroupStatusDto> = service.workflow(key, typeKey)

    // ---------- 组 ----------

    @GetMapping("/projects/{key}/groups")
    fun listGroups(
        @PathVariable key: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) level: Int?,
    ): List<ItemGroupService.GroupDto> = service.listGroups(key, type, level)

    @PostMapping("/projects/{key}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(
        @PathVariable key: String,
        @RequestBody request: ItemGroupService.CreateGroupRequest,
    ): ItemGroupService.GroupDto = service.createGroup(key, request)

    @GetMapping("/projects/{key}/groups/{groupId}")
    fun getGroup(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
    ): ItemGroupService.GroupDto = service.getGroup(key, groupId)

    @PatchMapping("/projects/{key}/groups/{groupId}")
    fun updateGroup(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
        @RequestBody request: ItemGroupService.UpdateGroupRequest,
    ): ItemGroupService.GroupDto = service.updateGroup(key, groupId, request)

    @DeleteMapping("/projects/{key}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGroup(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
    ) = service.deleteGroup(key, groupId)

    /** 动作端点:查 currentStatus 出边,无匹配 function → 409「当前状态无此动作」。complete 可带处置计划(spec 0110)。 */
    @PostMapping("/projects/{key}/groups/{groupId}/actions/{function}")
    fun action(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
        @PathVariable function: String,
        @RequestBody(required = false) request: ItemGroupService.GroupActionRequest? = null,
    ): ItemGroupService.GroupDto = service.action(key, groupId, function, request)

    @GetMapping("/projects/{key}/groups/{groupId}/items")
    fun items(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
    ): List<ItemService.ItemDto> = service.items(key, groupId)

    // ---------- 派生视图 ----------

    @GetMapping("/projects/{key}/backlog")
    fun backlog(
        @PathVariable key: String,
    ): List<ItemService.ItemDto> = service.backlog(key)

    @GetMapping("/projects/{key}/board")
    fun board(
        @PathVariable key: String,
    ): ItemGroupService.BoardDto = service.board(key)

    /** 归属替换(按组类型正交):{groupId|null, groupType}。 */
    @PutMapping("/items/{itemId}/group-membership")
    fun replaceMembership(
        @PathVariable itemId: UUID,
        @RequestBody request: ItemGroupService.MembershipRequest,
    ): ItemService.ItemDto = service.replaceMembership(itemId, request)

    /** 看板拉入/退回(spec 0099):{onBoard}。拉入同时挂最早 active sprint;退回同时移出非 completed sprint 成员行。 */
    @PutMapping("/items/{itemId}/board-membership")
    fun boardMembership(
        @PathVariable itemId: UUID,
        @RequestBody request: ItemGroupService.BoardMembershipRequest,
    ): ItemService.ItemDto = service.boardMembership(itemId, request)
}
