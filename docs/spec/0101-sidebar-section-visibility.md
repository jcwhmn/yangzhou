
# 0101 — 侧栏段数据驱动显隐(YPJ-150)

> YPJ-105 ItemGroup 元模型后续;承接 spec 0100 切片C 验收反馈(无组项目侧栏 Sprint「+」仍显示)。
> **2026-11-06 评审升级(用户拍板,案B)**:没有配置 item group 的场景,系统不应出现 "sprint" 这个词——该概念完全由用户配置产生。

## 〇、案B 终版设计(覆盖下文一~三中的初版判据)

- **侧栏 Sprint 段**:显隐判据维持「存在 level-1 类型」,但**段头文案 = level-1 类型名**(如 "Iteration"),不再是硬编码 "Sprint";新建按钮 aria-label = `新建 ${类型名}`;空态 = `暂无${类型名}`;建组对话框标题 = `新建${类型名}`。无类型 → 无段。
- **看板空态**:项目无 level-1 **组**时 → 中性文案 `t.board.noGroups`("项目未配置组——以下显示全部非终态 item");有组无 active → 原 `noActiveSprint`。
- **overview Sprint 概况段**:无 level-1 类型 → 整段隐藏;有 → 段头 = 类型名,空态 = `暂无${类型名}`。
- **flow**:无 level-1 组 → burnup 空态用中性 `t.flow.noGroups`("暂无进行中的组")。
- **item 详情**:sprints 为空(无组)→ 不渲染 Sprint Dropdown(标签 "Sprint" 本身即违规字样)。
- **Backlog 入口保留**(builtin,用户拍板;将来可入项目设置 enable 开关 = YPJ-151 语义)。
- 类型管理弹窗关闭时重拉(loadSprints → 连带刷新 l1),新建类型后段即时出现。

## 一、初版需求(案B前,存档)

