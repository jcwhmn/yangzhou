# 0074 — YPJ-86 单测补齐·Item 域(attribute/dependency/checklist)

> 票:YPJ-86 · 影响:`backend/api/src/test`(纯新增测试,零生产代码改动)

## 1. 需求

unit > integration 目标下的分域补齐第一票。item 包已有 `ItemServiceUnitTest`/`ItemDueSignalsUnitTest`,本票覆盖 **attribute / dependency / checklist** 三个 service 的全部业务分支,沿用 V67 后 mockk 样板(nested class 按方法分组,断言 kotlin.test)。

### 范围

1. `AttributeServiceUnitTest`:create(kind 白名单/重名 409/category 挂父 400/parent 非 category 400/parent 404)、update(category 改 kind 400/parent 校验/DataIntegrityViolation → 409)、delete(分类有子 409/被引用 409)、list(叶子带分类名)
2. `DependencyServiceUnitTest`:list(依赖行+blocked 聚合)、add(404/跨项目 400/重复 409/成环 400)、remove(404)、blockedItemIds(未终态阻塞,终态不阻塞)、成环检测 BFS
3. `ChecklistServiceUnitTest`:get/add(position 递增、trim)、patch(空 text 400/done 翻转)、remove(404)、doneCount

### 非目标

- 不动生产代码;不删既有集成测试(双轨并存,集成守契约,单测守分支)
- item 包已有单测不重复覆盖

## 2. 详细设计

- 仓库接口全部 `mockk(relaxed = true)`,每测试 `clearMocks`;`AttributeService` 依赖 `WorkspaceService` 同样 mockk(`required()` 返回固定 workspace)
- 实体用 data class 直构(id/objectId 手工指定);时间字段 `Instant.now()` 直构(非被测行为)
- 断言:异常类型 + 中文消息前缀;数值/装配字段逐一比对

否决:为可测性重构 service(超出本票范围,触碰生产代码另票);用 Fake 手写仓储(mockk 已是仓内样板,不自造)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| 1 | `gradle :api:test --tests "yangzhou.api.business.attribute.*" --tests "yangzhou.api.business.dependency.*" --tests "yangzhou.api.business.checklist.*"` | 全绿,无 Spring 启动日志(纯单测) |
| 2 | `gradle build` | 绿 |

## 4. As-built

- 三个单测文件全绿:AttributeServiceUnitTest(11 例)/ DependencyServiceUnitTest(10 例)/ ChecklistServiceUnitTest(8 例),零生产代码改动
- 踩坑两条(沉淀):① CrudRepository.save 的泛型 S 在 verify{} 内类型推断失败 → 改 answers{} 里 firstArg 捕获断言;② mockk 后置 `every { f(any()) }` 会覆盖前置具名 stub → 通用 null stub 先写,具名 stub 后写
- `gradle build` 绿
