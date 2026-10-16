package yangzhou.api.metrics

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** YPJ-45 Flow Metrics 只读 API(spec 0037 §四 / 0038)。纯读,无状态变更。 */
@RestController
@RequestMapping("/api")
class MetricsController(private val service: MetricsService) {

    @GetMapping("/projects/{key}/metrics/workload")
    fun workload(@PathVariable key: String): WorkloadResponse = service.workload(key)

    @GetMapping("/projects/{key}/metrics/throughput")
    fun throughput(
        @PathVariable key: String,
        @RequestParam from: String?,
        @RequestParam to: String?,
        @RequestParam(defaultValue = "week") bucket: String,
        @RequestParam groupId: UUID?,
    ): ThroughputResponse = service.throughput(key, from, to, bucket, groupId)

    @GetMapping("/projects/{key}/metrics/flow-times")
    fun flowTimes(
        @PathVariable key: String,
        @RequestParam from: String?,
        @RequestParam to: String?,
    ): FlowTimesResponse = service.flowTimes(key, from, to)

    @GetMapping("/projects/{key}/metrics/cfd")
    fun cfd(
        @PathVariable key: String,
        @RequestParam from: String?,
        @RequestParam to: String?,
    ): CfdResponse = service.cfd(key, from, to)

    @GetMapping("/projects/{key}/metrics/aging")
    fun aging(@PathVariable key: String): AgingResponse = service.aging(key)

    @GetMapping("/projects/{key}/metrics/burndown")
    fun burndown(
        @PathVariable key: String,
        @RequestParam groupId: UUID,
    ): BurndownResponse = service.burndown(key, groupId)

    @GetMapping("/projects/{key}/metrics/burnup")
    fun burnup(
        @PathVariable key: String,
        @RequestParam groupId: UUID,
    ): BurnupResponse = service.burnup(key, groupId)

    @GetMapping("/projects/{key}/metrics/scope-change")
    fun scopeChange(
        @PathVariable key: String,
        @RequestParam groupId: UUID,
    ): ScopeChangeResponse = service.scopeChange(key, groupId)

    @GetMapping("/projects/{key}/metrics/reopen-rate")
    fun reopenRate(
        @PathVariable key: String,
        @RequestParam from: String?,
        @RequestParam to: String?,
    ): ReopenRateResponse = service.reopenRate(key, from, to)


    @GetMapping("/projects/{key}/metrics/velocity")
    fun velocity(@PathVariable key: String): VelocityResponse = service.velocity(key)
}
