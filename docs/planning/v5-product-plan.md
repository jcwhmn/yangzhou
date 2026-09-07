---
title: V4+ 产品规划(功能池与页面全景)
created: 2026-08-30
tags: [yangzhou, 产品规划, V5]
---

# yangzhou V4+ 产品规划(草案讨论稿)

> PL:Yang(我,AI)· 后端+运维:Jcwhmn(你)· 本文是活文档,随讨论更新

---

## 一、已完成功能盘点(V1–V4)

### 引擎
- 匹配引擎:可行性判定 5 形态(满足/有余/差级/未评级/缺能力)+ 绿黄红聚合
- 团队候选:rankCandidates(缺门少→差距小,无加权,全池或项目池)
- 差距分析:逐条判定行 + 缺门数/总差距

### 后端
- **项目**:key/名称/默认 workflow(data-driven 四态)/isStart+isFinal/成员池语义(无行=全池)
- **item**:自动编号(key-N)/type/task 树(parentId 防环)/assignee(删人置空)/external_ref(部分唯一索引)/需求(词表引用,整表替换)/活动日志(7 种 kind,actor 留痕)
- **词表**:kind(skill/label)× leveled;切换不删数据
- **成员**:登录账号+虚拟成员(无凭据);Team 分组(纯池)
- **Workflow**:isStart/isFinal/迁移表(空=自由)/增删改/排序
- **规则**:开工须有主/角色永不进匹配/外部 ref 幂等

### CLI(yz)
login / members add·list·rm·set / teams add·list / attrs create·list / projects create·list / items create·list·move / candidates / assign / sync-linear / export / import / feasibility

### Web(7 页)
/login · /(项目列表+信号)· /p/[key] 看板(拖拽+过滤)· /p/[key]/i/[id] 详情 · /capabilities · /attributes · /members

### 联邦
Linear CSV 单向拉取(external_ref 幂等 upsert)

### 质量基线
53+ 黑盒/单测;CI 绿(GitHub Actions)

---

## 二、功能候选池(V5 方向)

### A. 流程与纪律
| 功能 | 说明 | 优先级 |
|---|---|---|
| **WIP 限制** | 每列最大数量,超出禁止拖入 | ⭐⭐⭐ |
| **item 依赖** | blocks / blocked by | ⭐⭐ |
| **评论**(人工) | item 讨论串(区别于自动留痕) | ⭐⭐ |
| **检查清单** | item 内 checklist | ⭐ |
| **Workflow 显式终点约束** | 终态后禁止再改(可配置) | ⭐ |

### B. 视图与分析
| 功能 | 说明 | 优先级 |
|---|---|---|
| **每日站会视图** | 按人分组 yesterday/today/blockers | ⭐⭐ |
| **短板面板深化** | 跨项目聚合、趋势图 | ⭐⭐ |
| **全局搜索** | 标题/描述全文 | ⭐⭐ |
| **活动聚合 feed** | 跨项目时间线 | ⭐ |
| **日历视图** | 按 due date | ⭐ |

### C. 协作与联邦
| 功能 | 说明 | 优先级 |
|---|---|---|
| **Linear 双向同步** | 回写到 Linear(状态/指派) | ⭐⭐⭐ |
| **通知** | 站内/webhook | ⭐⭐ |
| **GitHub 联邦** | issue/PR 映射 | ⭐ |
| **评论 @mention** | | ⭐ |

### D. 架构与工程
| 功能 | 说明 | 优先级 |
|---|---|---|
| **测试分层** | service 单测(MockK)引入 | ⭐⭐⭐ |
| **E2E 测试** | Playwright | ⭐⭐ |
| **部署** | docker-compose | ⭐⭐ |
| **Excel 导出** | | ⭐ |
| **回收站**(软删除) | | ⭐ |
| **API Token 管理** | 多 token/过期 | ⭐ |

---

## 三、页面全景

### 已实现 7 页
| # | 路由 | 功能 | 状态 |
|---|---|---|---|
| 1 | /login | 登录(bootstrap-or-login) | ✅ |
| 2 | / | 首页(项目卡片+可行性信号) | ✅ |
| 3 | /p/[key] | 看板(列+拖拽+过滤+需求摘要) | ✅ |
| 4 | /p/[key]/i/[id] | item 详情(编辑/判定/谁来做/活动) | ✅ |
| 5 | /capabilities | 能力自评(逐属性) | ✅ |
| 6 | /attributes | 词表管理(kind/leveled) | ✅ |
| 7 | /members | 成员管理(虚拟+能力) | ✅ |

### 规划页(按优先级)
| # | 路由 | 功能 | 优先级 |
|---|---|---|---|
| 8 | /p/[key]/settings | 项目设置(workflow 编辑+成员池+项目信息) | 高 |
| 9 | /activities | 全局活动流(跨项目) | 中 |
| 10 | /search | 全局搜索 | 中 |
| 11 | /teams | Team 分组管理 UI(backend 已有) | 中 |
| 12 | /standup | 每日站会视图 | 低 |
| 13 | /calendar | 日历视图 | 低 |
| 14 | /recycle | 回收站 | 低 |

---

## 四、菜单结构提案

### 现状
AppNav: **项目 | 我的能力 | 词表 | 成员**

### 提案甲(最小变更)
**项目 | 我的能力 | 词表 | 成员** + 看板内增加「设置」按钮(进项目设置页)

### 提案乙(完整信息架构)
**工作台**(首页+短板面板) | **项目**(列表→看板→详情) | **分析**(短板/活动) | **管理**(词表/成员/设置)

→ 推荐方案乙(导航扩展到 V5+ 不用改结构);看板页从首页项目卡进入(层级清晰)

---

## 五、逐页设计(function / look / operation)

### /login 登录页
- **功能**:bootstrap-or-login;token 存 localStorage
- **look**:居中卡片,产品 logo + 表单(用户名/密码);背景品牌色渐变
- **operation**:提交 → 验证 → 存 token → 跳首页;错误显示在表单下方(红字)

### / 首页
- **功能**:项目卡片(KEY/名称/可行性信号 chip)+ 创建表单 + 短板面板(属性聚合+涉及 item 链接)
- **look**:卡片 grid + 短板面板(表格式,按缺口严重排序);骨架屏加载
- **operation**:点卡片进看板;创建表单提交 → 新卡片出现

### /p/[key] 看板
- **功能**:状态列(数据驱动)+ item 卡片(编号+assignee/标题/需求摘要)+ 拖拽改状态 + assignee 过滤 chips + 工作流编辑按钮 + 成员池按钮
- **look**:横向滚动列,卡片圆角(信号边框色:绿/黄/红);列头(名称+计数+isStart/isFinal 标记)
- **operation**:拖卡 → PATCH status;点卡片 → 详情;工作流按钮 → 编辑面板

### /p/[key]/i/[id] item 详情
- **功能**:标题/描述/type 编辑 + 保存;状态切换;assignee chip + 取消;需求 Dialog(增删改);谁来做(候选分色+指派+assign me);活动时间线;删除(二次确认)
- **look**:分区(基本信息 → 需求入口 → 谁来做 → 判定 → 活动时间线);候选卡按信号边框色;VerdictLine 五形态颜色
- **operation**:保存/状态切换/指派/需求保存 全即时反馈("已保存"闪现 + 数据刷新)

### /capabilities 能力自评
- **功能**:逐属性自评(无/未评级/Lv1–4),改动即存
- **look**:属性列表 + Select 行;分组显示(有值在前)
- **operation**:Select 变更即 PUT

### /attributes 词表管理
- **功能**:属性增删改(名称/kind/leveled);kind 切换不删数据
- **look**:列表 + 表单;leveled 开关
- **operation**:即改即存;删除需确认(被引用时 409)

### /members 成员管理
- **功能**:虚拟成员增删;逐成员能力自评(同 /capabilities 交互)
- **look**:成员卡片列表 + 每卡片能力行
- **operation**:即改即存;登录账号不可删(409)

---

## 六、V5 Sprint 切票建议

按上述功能候选池 + 页面规划,PL 建议的 V5 切票(估 6 周,2 人):

| Sprint 周 | 内容 | 产出 |
|---|---|---|
| W1 | 项目设置页(workflow 编辑+成员池聚合) | 前端+联调 |
| W2 | item 删除+PATCH type(后端)+看板过滤(前端) | 后端+前端 |
| W3 | 评论(后端+前端) | 后端+前端 |
| W4 | 全局搜索(后端+前端) | 后端+前端 |
| W5 | 站会视图(前端) | 前端 |
| W6 | 回归+修复+发布 | 全量 |

---

## 七、Defer 清单(V5 后)

多 workspace/SaaS · RBAC/角色层级 · 定制化架构(SPI/authoring/installer) · GitHub 联邦 · Excel 导出 · 通知 · 甘特 · 工时 · 回收站 · E2E 测试 · 英文 UI
