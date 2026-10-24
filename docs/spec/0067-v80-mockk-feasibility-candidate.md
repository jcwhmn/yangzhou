# 0067 — YPJ-80 mockk 子票①:feasibility + candidate 判定链单测

> 票:YPJ-80(YPJ-38 子票①)· 影响:`backend/api/src/test`(仅新增单测)· mockk 依赖已在(YPJ-43)

## 1. 需求

api service 层 mockk 单测第一批:feasibility + candidate 的装配/分支逻辑。引擎(MatchingEngine)是纯函数,**真跑不 mock**;mock 只用于 repository/clock 等协作对象——本票测的是 service 装配与聚合分支,不是引擎本身。

### 范围

1. `FeasibilityServiceUnitTest`:
   - `project(key)`:装配正确(item→domain 映射、rollup 聚合 signal/missingCount/totalDelta、number 拼 key-N)、项目不存在 404;
   - `recomputeProjectSignal`:worst 聚合(RED>YELLOW>GREEN)、无 item = null、写回 project.feasSignal;
   - `recomputeWorkspace`:按 id 升序重算(YPJ-26 锁序约定,验证调用序);
   - `workspaceShortfall`:gap/unrated/missing 归类聚合、GREEN 项目被 runCatching 吞掉不炸;
2. `CandidateServiceUnitTest`:
   - `candidates`:排序 rank 从 1 连续、池约束(restrictedPool 过滤)、virtual 判定(passwordHash == null)、Verdict 三态到 DTO 映射(gap 带 delta/unrated 带 required/satisfied 仅有名)、item 不存在 404。

### 非目标

- 引擎分支(4 形态/绿黄红聚合规则)——domain 模块已测;
- 集成测试(既有 FeasibilityApiTest/CandidatesApiTest 覆盖)。

## 2. 详细设计

- 新文件:`src/test/kotlin/yangzhou/api/business/feasibility/FeasibilityServiceUnitTest.kt`、`.../candidate/CandidateServiceUnitTest.kt`;
- mockk `relaxed = true` mock 全部 repository + MemberService/WorkspaceService/ProjectMemberService;`every { }` 打桩最小化;
- 实体构造:持久层 Item/Member/Project 手工 new(主构造直建,同既有 ItemServiceUnitTest 风格);
- 断言 kotlin.test;目标 ~15–20 用例;
- 不动 main 代码(若发现 private 可测性缺口,不顺手重构——记录即止)。

否决备选:走集成补(已有);mock 引擎(本票恰恰要真跑引擎)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle :api:test --tests "*FeasibilityServiceUnitTest" --tests "*CandidateServiceUnitTest"` | 全绿 |
| TC2 | `gradle build --no-daemon` | 全绿(含既有单测回归) |


## As-built(2026-10-24,PR #138 合入)

- 按设计落地:FeasibilityServiceUnitTest 12 例 + CandidateServiceUnitTest 6 例;零 main 改动。
- 实现期固化进用例的三个发现:①引擎 level > min = surplus(非 satisfied);②recomputeProjectSignal 写回的是 project.copy(),断言须打在 save 实参;③mockk 泛型 save<S> relaxed 返回占位对象会 CCE,须显式 `val arg: T = firstArg()`(ItemServiceUnitTest P0 坑同源)。
- 验证:两单测类绿;用户 gradle clean build 绿;8080/8081 bootRun 正常。
