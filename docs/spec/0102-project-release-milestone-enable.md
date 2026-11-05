# 0102 — 项目级 release/milestone enable 开关(YPJ-151)

> 承接 spec 0101 拍板:Backlog = builtin 保留;release/milestone 可由项目配置启停。

## ⚠ 契约变更(置顶,显要标注)

- `project` 表加两列(默认 true,存量无感);`ProjectDto` 新增 `releaseEnabled` / `milestoneEnabled` 字段——**响应形状变更**(加字段,向后兼容)。
- 新增端点 `PATCH /api/projects/{key}/settings`——此前项目无任何更新端点,属新增能力。

## 一、需求

### 范围

- 项目可配置 release / milestone 两个开关,默认开;关闭后侧栏不渲染对应段(含段头/管理入口/空态),管理器(MilestoneManager/ReleaseManager)入口一并隐藏。
- 项目设置 UI 提供两个开关。
- 仅 UI 门控:milestones/releases 的读写端点行为不变(不做服务端 403/409 拦截;关开关只是"不显示",数据与 API 不动——最小实现,需要硬拦截再立票)。

### 非目标

- Sprint 段显隐(spec 0101 已做,判据不同:level-1 类型存在性)。
- Backlog 开关。
- 服务端写拦截(禁用后建 milestone 的保护)。

## 二、详细设计

### 后端(按文件)

- **V49 迁移**:`alter table project add column release_enabled boolean not null default true; add column milestone_enabled boolean not null default true;`(命名约束;无新表→cleanDatabase 不动)
- `Entities.kt` `Project`:`val releaseEnabled: Boolean = true, val milestoneEnabled: Boolean = true`
- `ProjectService`:
  - `ProjectDto` + `releaseEnabled` / `milestoneEnabled`(映射处同步)
  - 新增 `updateSettings(key, req)`:settings PATCH 落库(copy + save,走标准 save 更新)
  - `data class UpdateProjectSettingsRequest(releaseEnabled: Boolean?, milestoneEnabled: Boolean?)`(null=不改)
- `ProjectController`:`@PatchMapping("/projects/{key}/settings")`
- `api.http`:projects 区块补 PATCH 正例 + 响应 handler 回填(无负例:两字段均可空、无约束)

### Web(按文件)

- `lib/projects.ts`(或现有 project api 文件,找到项目详情获取处):类型补两字段;新增 `updateSettings(key, body)`
- `layout.tsx`:
  - loadSide 并行拉 `GET /api/projects/{key}` → state `flags`(release/milestone;加载中按 true 渲染防闪,失败按 true)
  - Milestone / Release 段(含 ListSubheader + 管理按钮 + 条目)分别包 `flags.milestoneEnabled` / `flags.releaseEnabled`
- **项目设置 UI**:新增轻量 `ProjectSettingsPanel.tsx`(模式照抄 GroupTypesPanel:Dialog + 两枚 MUI Switch + 保存 → PATCH → onChanged 刷新);入口 = 侧栏头部设置区加一个图标按钮(与成员/类型管理并列);文案入 `texts.ts`
- `MilestoneManager` / `ReleaseManager` 本身零改动

### e2e

- 新 spec `project-settings.spec.ts`:默认两段都在 → UI 关 milestone → 段消失(刷新后仍在)→ 重开 → 段回来
- projects.spec 不动(默认开,存量断言不受影响)

## 三、手工测试用例(用户 build/debug)

1. YPJ(dev):两段照常;设置面板关 milestone → 刷新后侧栏无 Milestone 段,Release 段还在;重开恢复
2. 关 release:同上对称
3. api.http:PATCH 正例过,GET /projects/{key} 能看到新字段
4. 存量回归:Backlog/Sprint 段与 0101 行为不受影响
