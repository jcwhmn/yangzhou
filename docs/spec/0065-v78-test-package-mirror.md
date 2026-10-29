# 0065 — YPJ-78 yz-api test 侧包结构镜像重构

> 票:YPJ-78 · 影响:仅 `backend/api/src/test` · 判据同 ADR-0007
> 性质:纯移动 + package/import 修正,零行为变更。

## 1. 需求

YPJ-71 只平移了 main 侧;test 侧目录/包声明仍停在 `yangzhou.api.<slice>`,靠 import 指向 `business.<slice>`,与被测类不再镜像。本票把 test 目录对齐。

### 范围

- test 侧全部切片测试目录/包声明 → `yangzhou.api.business.<slice>`;
- 原 `config` 包下 3 个测试类按 ADR-0007 归位:`JwtServiceUnitTest` → security、`GlobalExceptionHandlerTest` → error、`AccessLogFilterTest` → general;
- 其余(`AbstractApiTest`、`support` 等 test 基础设施)不动,除非 import 需修正。

### 非目标

- main 侧任何改动;测试逻辑/断言改动;新增测试。

## 2. 详细设计

1. `git mv` `src/test/kotlin/yangzhou/api/<slice>` → `.../business/<slice>`(30 个切片目录);
2. 三个 config 测试类移入对应包;
3. python 脚本批量替换测试文件 package 声明(`package yangzhou.api.<slice>` → `business.<slice>`)及自引用 import;
4. 无新测试(零行为变更)。

否决备选:维持扁平 test 结构(Y7P-71 已裁决镜像,不再反复)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle build --no-daemon`(单测) | 全绿 |
| TC2 | `gradle integrationTest --rerun` | 全绿 |


## As-built(2026-10-24,PR #136 合入 4023241)

- 比设计更小:package 声明 YPJ-71 批量替换时已改为 `business.*`,本票实际只挪目录 + 3 个 config 测试类归位(security/error/general)。
- 验证:gradle build(单测)绿;integrationTest --rerun 全量绿。
