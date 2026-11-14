# spec 0127 — YPJ-187 项目工具栏统一:侧栏图标行移除 + 统一顶栏 + 设置收敛单一入口

> 状态:设计稿,待用户过目后编码。
> 用户拍板(2026-11-15):①侧栏 7 图标行去掉;②收藏与设置放「工时那一栏」(看板顶栏样式);③「项目栏应该统一的,都使用看板一样的」= 所有项目页共用同一顶栏;④设置收敛单一入口(轻量版:对话框内分节,不新开页面)。

## 一、需求

### 范围

1. **移除侧栏顶部图标行**(收藏星 + 工作流/成员/组类型/Item类型/设置/GitHub/导出 7 按钮);项目 key 标题行只留文本。
2. **统一顶栏 `ProjectToolbar`**:所有项目页(Overview·含看板/Flow/甘特/表格/Backlog/Milestone/Release/工时/Sprint 落地/Item 详情)顶部同一条栏,布局 = 看板那栏样式:
   `[快速添加 item 输入+按钮] …(右侧) [工时] [★收藏] [⚙设置]`
   - 快速添加:**仅 看板(`/p/{key}`)/backlog/level-1 组页(`/p/{key}/sprint/*`)三处显示**(2026-11-15 用户收窄);语义与各页现状一致(看板=S0–S3 组感知机,backlog=直建根 item),不统一语义只统一位置
   - 收藏星:与现侧栏同语义(toggle + 居项目旁)
3. **设置收敛**:新 `ProjectSettingsDialog`(单一对话框,**左侧节菜单 + 右侧内容区**),节 = 基本(release/milestone 启停 + 工作量刻度)/ 工作流 / 成员 / Item 类型 / 组类型 / GitHub / 导出。各节内容复用现有面板组件(逻辑零改动,容器换)。
4. Release 管理**不进设置**,留侧栏 Release 段「管理 Release」入口(现状不动)。

### 非目标

- 不做路由化设置页(/settings/members 子路由留将来,弹层够用)
- 不动各页页面自身的业务工具行(表格的视图/搜索行、看板的列筛选等不动,统一顶栏在其上方)
- 不动全局 AppNav(收藏下拉/跳转框)

## 二、详细设计

### 结构

- 新组件 `web/components/ProjectToolbar.tsx`:由 **layout 渲染一次**(侧栏右侧内容区顶部),天然全页覆盖,不逐页插。
  - props 从 layout 现状取:`projectKey`、fav/toggleFav、快速添加回调(layout 已有 createOpen 逻辑,平移)。
- 新组件 `web/components/ProjectSettingsDialog.tsx`:内部 `section` state;各节直接渲染既有组件:`ProjectSettingsPanel`(基本,去 Dialog 壳留内容)、`WorkflowEditor`、`ProjectMembersPanel`、`ItemTypePanel`、`GroupTypesPanel`、`GithubSettingsPanel`、导出按钮(调 layout 现有 exportXlsx)。
  - 既有面板组件改双模式:保留对外 Dialog 包装(向后兼容)或直接拆壳——**拆壳**:面板本体改纯内容组件,Dialog 壳由调用方套;本票后仅 hub 一处调用。
- layout:删图标行与 7 个 dialog 开关 state;保留各面板挂载但移入 ProjectSettingsDialog;`createOpen` 快速添加逻辑平移给 toolbar。

### 状态×动作真值表(顶栏)

| 页面 | 动作 | 结果 |
|----|----|----|
| 看板/backlog/组页 | 快速添加输入+回车 | 各页现语义不变(看板=组感知,backlog=根 item);其余页无快速添加 |
| 任意项目页 | 点★ | 收藏切换,AppNav 收藏下拉同步 |
| 任意项目页 | 点⚙ | 设置对话框开,默认「基本」节 |
| 设置开 | 左侧切节 | 右侧内容区切换,已填表单态随组件卸载丢失(与现状 Dialog 一致) |
| 设置开 | 保存/关闭 | 各节自管(现状语义不变) |

### 示意图

```
侧栏                     统一顶栏(所有项目页)
┌──────────┐  ┌───────────────────────────────────────────────┐
│ PROJ     │  │ [+ 输入item标题 快速添加]        [工时] [★] [⚙] │
│ ▦ Overview│  ├───────────────────────────────────────────────┤
│ ▦ Flow    │  │  页面自身内容(表格/看板/甘特…业务工具行不动)      │
│ ▦ 看板 …  │  └───────────────────────────────────────────────┘
```

设置对话框:

```
┌ 项目设置 ──────────────────────────────────┐
│ 基本        │  (release/milestone 开关)     │
│ 工作流      │  (工作量刻度编辑区)            │
│ 成员        │                              │
│ Item 类型   │                              │
│ 组类型      │                              │
│ GitHub     │                              │
│ 导出        │                              │
└────────────────────────────────────────────┘
```

### e2e 影响(同步改,断言语义不变)

- `project-settings.spec.ts`:入口「项目设置」按钮 → 顶栏⚙(aria-label「项目设置」保留,路径不变,用例大概率零改)
- `group-types.spec.ts`:「分组类型管理」按钮 → ⚙→组类型节(helpers.openGroupTypes 改两步)
- `item-type.spec.ts`:「Item 类型」→ ⚙→Item 类型节
- `projects.spec.ts`:卡片☆收藏不受影响(项目列表页);若引用侧栏 star 需改顶栏★
- 其余(看板/表格/backlog/sprint 等)入口不涉设置,不涉

### 否决的备选

- ~~各页逐一插 toolbar~~:layout 渲染一次天然全覆盖,防页面间漂移
- ~~设置做成独立路由页~~:弹层够用,路由化留将来(用户已选轻量版)
- ~~Release 管理进设置~~:Release 是侧栏日常段,非配置,留原位

## 三、手工测试用例(用户 build/debug)

1. 各页(Overview/Flow/甘特/表格/Backlog/Milestone/工时)顶栏都在且一致;快速添加建 item 后各页可见。
2. ★切换 → AppNav 收藏下拉同步;⚙ 开设置默认「基本」节。
3. 设置各节逐个打开可用:工作流编辑保存、成员面板、Item/组类型、GitHub PAT、导出 xlsx 下载。
4. 侧栏:图标行消失,项目 key 标题行干净;Release 段「管理 Release」仍在。
5. 旧入口回归:组类型/Item 类型 e2e 两文件改入口后跑绿;project-settings.spec 绿。
