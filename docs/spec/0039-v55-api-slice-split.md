# 0039 — YPJ-55 api 切片拆分:Controller 与 Service 分文件

> 票:YPJ-55。分支 `cwjiang/YPJ-55-api-slice-split`(开工时经 `yz items branch` 建立并关联)。

## ① 需求

**背景**:api 切片按垂直组织(实体+DTO+service+controller 同包),但 10 个切片把 Controller 与 Service 写进了同一文件——已达"吵了再拆"阈值,用户拍板全部拆为独立文件。**纯机械移动,零行为变更**。

**范围**(票面盘点清单,2026-10-15 复核与代码一致):candidate / checklist / dashboard / dependency / favorite / github×2(GithubBranch、GithubCommit)/ milestone / release / time。

**非目标**:不合并/重排端点;不拆 DTO 独立文件;不动 metrics 等已分文件的切片;不动 domain/persistence;不改端点契约与 OpenAPI 形状。

**验收**:
1. 10 处全拆;复扫 `grep -rl "@RestController" backend/api --include=*.kt | xargs grep -l "class \w*Service"` 为 0。
2. `gradle build` 绿;集成测试全绿(端点行为零变更由既有集成测试背书)。
3. entities.puml、api.http 不涉及。

## ② 详细设计

**规则**(票面已定):每处把 Controller 类(含其 import)移入**同包新文件 `XxxController.kt`**;DTO/Request/Response 留 Service 文件(同包互引无需 import);**文件名与主类一致**;imports 按需收敛;不动物理包结构。

### 逐文件动作(10 处)

| 现文件 | 动作 | 结果 |
|---|---|---|
| candidate/CandidateService.kt | 移出 `CandidateController` | + candidate/CandidateController.kt |
| checklist/ChecklistService.kt | 移出 `ChecklistController` | + checklist/ChecklistController.kt |
| dashboard/DashboardController.kt | 移出 `DashboardController`;原文件 git mv 更名 | + dashboard/DashboardController.kt;原文件 → dashboard/DashboardService.kt(留 LiveGroupDto/LiveSprintDto/LiveMilestoneDto/DashboardDto + DashboardService) |
| dependency/DependencyService.kt | 移出 `DependencyController` | + dependency/DependencyController.kt |
| favorite/FavoriteService.kt | 移出 `FavoriteController` | + favorite/FavoriteController.kt |
| github/GithubBranchService.kt | 移出 `GithubBranchController` | + github/GithubBranchController.kt |
| github/GithubCommitService.kt | 移出 `GithubCommitController` | + github/GithubCommitController.kt |
| milestone/MilestoneController.kt | 移出 `MilestoneController`;原文件 git mv 更名 | + milestone/MilestoneController.kt;原文件 → milestone/MilestoneService.kt(留 Create/UpdateMilestoneRequest + MilestoneService) |
| release/ReleaseController.kt | 移出 `ReleaseController`;原文件 git mv 更名 | + release/ReleaseController.kt;原文件 → release/ReleaseService.kt(留 Create/UpdateReleaseRequest + ReleaseService) |
| time/TimeEntryService.kt | 移出 `TimeEntryController` | + time/TimeEntryController.kt(原文件留 Service + CreateTimeEntryRequest) |

**import 收敛细则**:新 Controller 文件只保留 Spring web 注解、日志等 Controller 实际用到的 import;原文件删去仅被 Controller 使用的 import(典型 `org.springframework.web.bind.annotation.*`)。同包类型(Service、DTO)无需 import。

### 否决的备选

- **DTO 随 Controller 走**——票面已定 DTO 留 Service 文件(DTO 多为 Service 产出,「小切片 DTO 同文件」惯例不动);
- **顺带拆 auth/attribute 等疑似共存切片**——复核为 grep 误报(Controller 构造参数行 `class XxxController(private val service: XxxService)` 撞匹配),实际已分文件,不在范围;
- **脚本/正则批处理拆分**——10 处逐一手改,每处独立可编译验证;正则易搅 import 与字符串字面量,收益为负。

## ③ 手工测试用例(供 build/debug)

1. `gradle build --no-daemon` 绿(含集成测试)。
2. bootRun 起后 api.http 从「登录」整链重跑:抽查 milestone / release / dashboard 各 1 端点 200(被更名文件的切片)。
3. Scalar 页端点总数不变,响应形状不变。

## As-built(2026-10-16,PR #109 合入)

- **10 处全拆**:Controller 类移入同包新文件,DTO/Request/Response 留 Service 文件;dashboard/milestone/release 原文件以 Controller 命名,`git mv` 更名(文件名与主类一致);imports 按引用收敛(简单名 `` 检查,通配 import 保留)。
- **实现期修正(记坑)**:
  1. 票面验收复扫命令 `grep -rl @RestController | xargs grep -l "class \w*Service"` 会命中 `backend/api/bin/` 下 IDE 编译产物残留(6 个,git ignore 中)——**验收口径以 src 为准**;
  2. 脚本化拆分两次把新文件写到包根(输出路径丢切片子目录)、一次产生双 package 行——均被编译期暴露;多类同文件的块边界 = 类行向上吸收注解/KDoc、向下至下一顶层声明,类间夹的 DTO 随 Service 走;
  3. 拆分属机械变更但**编译验证不可省**:错位/重复声明全是编译期抓住的,零运行期症状。
- 偏差:无(端点契约、OpenAPI 形状、api.http、entities.puml 均未动)。
- 验证:复扫 src 口径 = 0;`gradle build` 绿 + `integrationTest` 绿;bootRun 临时端口冒烟通过;api.http 整链抽查于 8081 build/debug 环节完成。
- 同分支顺带:AGENTS.md 分支命名对齐 spec 0036 v2(`cwjiang/` 前缀废弃,用户纠正)。
