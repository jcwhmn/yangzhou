# 0101 — 侧栏段数据驱动显隐(YPJ-150)

> YPJ-105 ItemGroup 元模型后续;承接 spec 0100 切片C 验收反馈(无组项目侧栏 Sprint「+」仍显示)。

## 一、需求

### 范围

- 项目侧栏 **Sprint 段**(ListSubheader「Sprint」+「新建 +」按钮 + sprint 条目列表 + 空态)改为数据驱动:**项目存在 level-1 组类型才渲染**;否则整段隐藏。
- **Backlog 入口保留**(不受 level-1 存在性影响):无 level-1 时 backlog = 全部非终态 item(spec 0100 §五.3 语义),仍有用;渲染位置不动。

### 非目标

- Milestone/Release 段显隐 → YPJ-151(enable 开关)联动,本票不动。
- `sprint/[id]` 路由、看板/backlog 页本身。
- 侧栏其它段(anyway 静态)。

## 二、详细设计

- `web/app/p/[key]/layout.tsx`:
  - 新增 state `hasLevel1: boolean | null`(null=加载中,渲染段以避免闪烁;与现有 sprints === null 哨兵同风格)
  - loadSprints 时(或并列 useEffect)拉 `GET /api/projects/${key}/group-types`,取 `some(t => t.level === 1)`;失败(403/网络)按 false 处理=隐藏段(隐藏是安全默认)
  - 渲染:`hasLevel1 === true` 才渲染 Sprint ListSubheader(+按钮)与 sprint 列表/空态;Backlog ListItemButton 移到条件外,紧跟 table 项之后
- 无后端改动、无契约变更。

## 三、测试

### e2e

- `projects.spec` 首例:UI 建的新项目(`${projectKey}P`,无类型)断言侧栏**无**「新建 Sprint」按钮、无「暂无 Sprint」(翻转切片C加的空态断言)
- `group-types.spec` level 动线用例:删 fixture 种后断言无 Sprint 段;UI 建 timed 类型后断言段出现(含 + 按钮)

### 手工测试用例(用户 build/debug)

1. 新项目:侧栏无 Sprint 段(标题/+ 均无),Backlog 入口仍在
2. 类型管理加 timed 类型 → 侧栏段出现,可建组
3. 存量 YPJ:sprint 段一切如常
4. 删除唯一 level-1 类型(无组时)→ 侧栏段消失(重进项目页)
