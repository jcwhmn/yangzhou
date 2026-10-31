package yangzhou.api.business.dashboard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class DashboardController(
    private val service: DashboardService,
) {
    /** YPJ-2 全局驾驶舱:跨项目聚合——我的三桶(站会)+ 各项目活跃 sprint/进行中 milestone。 */
    @GetMapping("/dashboard")
    fun dashboard(): DashboardDto = service.dashboard()
}
