# 0028 YPJ-32:表格列显隐 localStorage 按项目 key 区分

2026-10-13 立项。状态:**完工**(As-built 见文末)。

## 需求

**票面**(grill 定稿):表格页列显隐 chip 已存在,状态组件内存,导航/刷新即重置 → 持久化到 **localStorage(按项目 key 区分)**,进页恢复。列配置整体重设计已立候选票(YPJ-28),不在本票范围。

**⚠ 前提与代码不符,请复核**:现状代码(V10-S4 起)**已有** localStorage 持久——但键是全局 `yz-table-cols`,**不分项目**;「组件内存,刷新即重置」的描述已过时。本票实做缺口 = **键按项目 key 区分**(A 项目的列定制不再串到 B 项目,这正是"看似没持久化"的体感来源)。按语义判例取与现有实现一致的读法:保留持久化机制,只改键作用域。若你确认票面描述另有所指(另有一个无持久化的表格页),叫停。

**非目标**
- 列配置重设计(YPJ-28)。
- 旧全局键 `yz-table-cols` 的数据迁移:dogfood 单用户,丢弃无碍,留成孤儿键。
- 不动 chip 交互形态、列默认值、排序。

## 详细设计

**仅改 `web/app/p/[key]/table/page.tsx`**(3 处小改,后端零改动):

- 抽 `readCols(k): Record<string, boolean>`(含 `typeof window` 守卫 + try/catch,原逻辑平移)。
- 键:`yz-table-cols` → **`yz-table-cols:${key}`**;`useState` 惰性初始化用 `readCols(key)`。
- 加 `useEffect(() => setCols(readCols(key)), [key])`:防 App Router 下同组件实例跨项目复用时读到旧项目状态(若实际是重挂载则该 effect 空转,幂等无害)。
- 写 effect 依赖 `[cols, key]`。

否决备选:迁移旧键(单用户不值);方案存 sessionStorage/后端(票面定死 localStorage;后端存储属 YPJ-28 重设计范畴)。

## 手工测试用例(build/debug 阶段)

1. 项目 A 表格:关「优先级」、开「阻塞」→ **刷新** → chip 与列恢复 A 的定制。
2. 切到项目 B 表格:**默认态**(不继承 A);回 A → 仍是 A 的定制。
3. 刷新后 chip 颜色与列可见一致(无反向)。
4. e2e 全量回归(无 table.spec,现有用例不受影响预期)。

## 验收对照(票)

- [x] 开关状态按项目持久化,进页恢复 → 手工 1/2(用户 build/debug 实测通过 2026-10-13)
- [x] 不串项目 → 手工 2

## As-built

> 2026-10-13 完工。与设计零结构性偏离。

- 仅改 `web/app/p/[key]/table/page.tsx`(+12/−6):`readCols(k)` 抽取(window 守卫 + try/catch 原逻辑平移)、键 `yz-table-cols:${key}` 读写同步、key-change 重读 effect、写 effect deps `[cols, key]`。
- 验证:`tsc --noEmit` 零新增(release.spec 3 错 main 既有);手工用例 1/2/3 用户实测通过。e2e 未跑(无 table.spec,现有用例与本改动无交集)。
