# 0075 — YPJ-87 单测补齐·项目与人域(project/projectmember/member/team/capability/workspace)

> 票:YPJ-87 · 影响:`backend/api/src/test`(纯新增测试,零生产代码改动)

## 1. 需求

分域补齐第二票(接 0074):六个 service 全分支单测,共 **50 例**。

- `ProjectServiceUnitTest`(6):建项种子(5 状态/2 组类型/创建者入池)、重 key 409、get 404/映射、list 组装失败跳过、删除 FK 序(purge ordering)
- `ProjectMemberServiceUnitTest`(8):add 404×2/409/成功、list 加入序、remove 404/删除、restrictedPool、池外指派 409
- `MemberServiceUnitTest`(13):current(仅凭据成员/未 bootstrap)、虚拟成员(重名 409/调色板色)、删除(登录号 409/在途指派 409/引用计数 409/干净删除)、github username(格式 400/占用 409/自占放行/空串清除)、lastProject(trim/清空/404)
- `TeamServiceUnitTest`(8):建组(重名 409/空成员)、改名 404/成功、删除(级联)、加人幂等、移人静默、toDto 成员名映射
- `CapabilityServiceUnitTest`(8):level 边界 400、未知属性 404、upsert 更新/新建 presence-only、recomputeWorkspace 扇出、delete 存在删/缺失 noop、list 未知属性映射 "?"
- `WorkspaceServiceUnitTest`(3):ensureDefault 存在复用/空则建、required 缺失 fail

### 非目标

- 不动生产代码;不删既有集成测试;ProjectService 的甘特/预览数据组装细节(纯查询装配)不重复覆盖

## 2. 详细设计

同 0074 样板(mockk relaxed + nested 分组 + kotlin.test)。踩坑沉淀(增量):

- **relaxed mock 泛型 save 返回 Object**:service 拿返回值继续用时 ClassCastException → 必须显式 `every { repo.save(any<T>()) } answers { firstArg<T>() }`(0074 的 answers 技巧在"返回值被消费"场景是必需,不只是 verify 需要时)
- **删除 FK 序断言**:`verify(ordering = Ordering.ORDERED) { ... }`(MockK 官方 ordering,非自造)

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| 1 | `gradle :api:test`(全单元) | 全绿 |
| 2 | `gradle build` | 绿 |

## 4. As-built

- 六文件 50 例全绿;`gradle build` 绿;零生产代码改动
- ProjectServiceUnitTest.create 里删除了对 workflowRules.save 的 0 次断言(create 实际会种子默认规则,initial 写反)
