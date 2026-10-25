# 0077 — YPJ-89 单测补齐·辅助域(auth/dashboard/standup/view/workflow/milestone/release)

> 票:YPJ-89 · 影响:`backend/api/src/test`(纯新增测试,零生产代码改动)

## 1. 需求

分域补齐收官票(接 0074/0075/0076):七个 service 全分支单测,共 **45 例**。

- `AuthServiceUnitTest`(5):bootstrap 二次 409/advisory 锁、登录错密/未知用户 BadCredentials、成功发 token
- `StandupServiceUnitTest`(4):三组归类(昨日推进∩未终态→done+today;终态→done only;阻塞=blocked∩today)、他人 item 排除、未知成员 404
- `DashboardServiceUnitTest`(3):active sprint / in_progress milestone 各自单独撑起「在途」、归档与非 active 过滤、全空则项目不出现
- `ViewServiceUnitTest`(10):空名/重名 409/非对象 JSON/超 4KB、null 存 `{}`、他人视图与跨项目视图均 404(不泄露存在性)、改名冲突仅当名字真变
- `WorkflowServiceUnitTest`(7):建状态重名 409/position 自增、删状态 FK 409/级联、replaceTransitions 未知状态 400+整表替换、空表自由迁移/表内放行/自迁放行/表外 409
- `MilestoneServiceUnitTest`(8):空名/坏日期 400、非法跳步 409、终态不可编辑×2、双 in_progress 409、合法迁移落库、404
- `ReleaseServiceUnitTest`(7):缺 release 类型 409/空名 400/坏状态 400/坏日期 400、已发布 touched 409×2、列表排序(planned 按 target 升序在前,released 按发布日降序在后)

### 非目标

- **metrics 不新建文件**:纯数学已被 MetricsMathTest 覆盖,service 层是仓储聚合装配(集成轨 MetricsApiTest 守护),mock 成本>收益
- DashboardService 只测 live 组装,standup 聚合细节在 StandupServiceUnitTest

## 2. 详细设计

同 0074-0076 样板(mockk relaxed + nested 分组 + kotlin.test)。踩坑沉淀(增量):

- `fun deleteByProjectId(...)` 返回 Unit 的方法 stub 用 `returns Unit`(`returns 0` 类型不匹配)
- 「删后重查」模式(replaceTransitions 删表再插,再 listTransitions)测试需同时 stub save 与 findByProjectId,否则第二次读走 relaxed 空列表

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| 1 | `gradle :api:test` | 全绿 |
| 2 | `gradle build` | 绿 |

## 4. As-built

- 七文件 45 例全绿;`gradle build` 绿;零生产代码改动
- YPJ-86~89 四票合计:business 包单测从 6 包有 → **29/30 包有**(仅 metrics 按 non-target 留给 math+集成)
