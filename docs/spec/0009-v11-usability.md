# Spec — yangzhou V11:产品可用性冲刺

> 来源:V10 收尾后用户测试反馈(2026-09-19/26,原 repo requirement.md,已迁 Obsidian projects/yangzhou/Thoughts.md「来自 repo requirement.md」)+ V10 未完成前端缺口;决策记录见 Obsidian CONTEXT.md「V11 已定」。词汇表/ADR 同 V1–V10。

## Problem Statement

V10 后端/API 已完备,但前端 UI 存在系统性缺口:项目列表无筛选排序、无上次项目记忆、控件边框廉价、item detail 未重构(tab/sidebar)、members 无多层表头。这些是用户日常使用的直接痛点。

## Solution(五块)

1. **登录后进入上次项目**:`member.last_project_key` 后端持久;登录成功后自动跳转。
2. **全局去边框**:MUI theme 覆写,TextField/Select 全局 standard(无边框)。
3. **项目列表增强**:文本筛选/排序(名称/信号)/归档 checkbox 切换。
4. **item detail tab 重构**:常驻区(标题/描述/日期/优先级/保存) + 5 tab(详情/需求/依赖+清单/工时/GitHub) + 右 sidebar(创建时间/超期/临期/阻塞) + 评论常驻底部(不做 tab) + priority Select 接通。
5. **members 颜色状态**:池内成员颜色标记 item 状态(有进行中/已完成/未分配)。

## User Stories

1. As a 用户, I want 登录后直接进入上次工作的项目, so that 不用每次从列表找。
2. As a 用户, I want 界面控件无边框, so that 视觉干净不廉价。
3. As a 管理者, I want 项目列表按名称搜索、按信号排序, so that 快速定位项目。
4. As a 用户, I want item 详情分 tab 展示, so that 关注哪个区块就点哪个。
5. As a 管理者, I want members 页用颜色标记成员工作状态, so that 谁在忙一眼可见。

## Implementation Decisions

- **V26 migration**:`member.last_project_key varchar(10)` 可空
- **API**:`PUT /api/members/me/last-project {key}`;登录成功后前端读 member.lastProjectKey 自动跳转
- **去边框**:MUI theme 覆写 `MuiTextField defaultProps.variant = "standard"`、`MuiSelect` 同理;全局一处改
- **priority**:V24 `item.priority varchar(2)`(CHECK P0–P3 或空);PATCH 语义 不传=不变 / 空串=清除 / P0–P3=设置;detail 常驻区 Select 接通
- **detail sidebar**:创建时间来自 ItemDto.createdAt;超期/临期/阻塞复用 list 读模型字段(overdue/dueSoon/blocked);更新时间不上(后端 update 路径不维护 updatedAt,展示会失真);item 级收藏不存在,不做
- **members 表格**:MUI Table 两行表头(分类 colspan + 叶子名),body 行 = 成员;checkbox(presence)+ TextField number(等级 1–4)
- **成员色点**:`🟢` 有非终态 assignee item / `⚪` 无;数据从 `GET /api/projects/{key}/items` 按成员聚合

## Out of Scope(→ Defer 总账)

websocket 实时 · rich text(专门 sprint) · 到期提醒 · Excel 导入 · i18n · Linear 同步 · item 级收藏

## Further Notes

- 切票:S1 登录跳转(V26)→ S2 全局去边框 → S3 项目列表增强 → S4 detail tab 重构 + priority 接通 → S5 members 表格 + 颜色状态 → S6 E2E + 收尾
- **工作方式变更**:本 sprint 起自主循环(创建 PR → 自行合并 → post-merge 清理 → 下一票),不再等用户审阅
