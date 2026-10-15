package yangzhou.api.milestone

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
import yangzhou.persistence.Milestone
import jakarta.validation.Valid
import java.util.UUID

/**
 * V14 Milestone:项目时间轴目标节点(PRD §9–12,spec 0011)。非 ItemGroup,独立实体。
 * 生命周期 planned→in_progress→completed(可提前 cancelled);终态不可变(§11 历史保留);
 * 0..1 in_progress:服务层预检 409,DB 部分唯一索引 ux_milestone_project_current 兜底。
 */
@RestController
@RequestMapping("/api")
class MilestoneController(private val service: MilestoneService) {

    @GetMapping("/projects/{key}/milestones")
    fun list(@PathVariable key: String): List<MilestoneService.MilestoneDto> = service.list(key)

    @PostMapping("/projects/{key}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateMilestoneRequest,
    ): MilestoneService.MilestoneDto = service.create(key, request)

    @PatchMapping("/projects/{key}/milestones/{milestoneId}")
    fun update(
        @PathVariable key: String,
        @PathVariable milestoneId: UUID,
        @RequestBody request: UpdateMilestoneRequest,
    ): MilestoneService.MilestoneDto = service.update(key, milestoneId, request)

    @DeleteMapping("/projects/{key}/milestones/{milestoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable key: String, @PathVariable milestoneId: UUID) = service.delete(key, milestoneId)
}
