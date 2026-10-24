# 0068 — YPJ-81 mockk 子票②:sprint 状态/成员规则单测

> 票:YPJ-81(YPJ-38 子票②)· 影响:仅新增 `SprintServiceUnitTest`

## 1. 需求

SprintService 的规则分支 mockk 单测:assignItem 成员指派/替换语义(替换式写、completed 不可指、null=移出、留痕)与 update 校验(status 枚举、name 空校验)。

### 范围

- `assignItem`:item 404;sprintId=null 移出(有/无 current);sprint 404;completed sprint 409;同 sprint 幂等;换 sprint = 删旧留痕 + 存新留痕;无 current 直加;
- `update`:status 非法 400;name 空白 400;合法变更落库;
- 留痕验证:recordAdd/recordRemove 调用次数与参数(clock 注入 Instant)。

### 非目标

- board/backlog/list 查询装配(集成已覆盖);引擎无关。

## 2. 详细设计

- 新文件 `src/test/kotlin/yangzhou/api/business/sprint/SprintServiceUnitTest.kt`;
- 协作者 mockk(relaxed);ItemService 整体 mock;Clock.fixed 钉死时刻;
- 泛型 save 坑规避:`memberships.save` 显式 `val arg: ItemGroupMember = firstArg()`。

否决:集成补(已有覆盖);不动 main。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle :api:test --tests "*SprintServiceUnitTest"` | 全绿 |
| TC2 | `gradle build --no-daemon` | 全绿 |


## As-built(2026-10-24,PR #139 合入)

- 按设计落地:SprintServiceUnitTest 11 例(assignItem 8 + update 3);零 main 改动。
- mockk 坑三条(已注释):clearMocks 勿碰非 mock fixedClock;relaxed 对未知 UUID 造非空占位(显式压 null);ItemGroupMember.createdAt 默认 now() 破坏 data class 相等(钉 EPOCH)。
- 验证:单测绿;用户 gradle clean build 绿;8080/8081 bootRun 正常。
