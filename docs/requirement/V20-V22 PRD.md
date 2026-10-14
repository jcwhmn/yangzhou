# 后续需求 PRD(V20–V22 + backlog)

> 2026-10-14 立版。范围 = 当前 sprint 阶梯的后续需求;票面(YPJ-45~51/28/20/38)为对应实例,本 PRD 是其统一需求叙事。
> 上游材料:Plane 功能图谱 + kaneo user story 对标(已迁 Obsidian `projects/yangzhou/`)。
> 配套:`docs/planning/roadmap.md`(排期)、`docs/spec/0033-v19-sprint-design.md`(V19 伞设计)。

---

## 1. Flow Metrics 价值闭环(V20;YPJ-45/46)

### 1.1 背景与用户价值

项目管理软件的 metrics 不是「做一个漂亮 Dashboard」,而是回答六个问题:**我们有多少工作?流得快不快?能不能按时交付?质量如何?堵在哪里?计划准不准?** 对标双方(Plane 付费墙、kaneo)在此域均缺或浅,是本产品差异化主攻点。

前置:V19 基座已交付 item 级状态迁移审计(`item_status_history`,spec 0032)——任意路径迁移留痕、存量回填保留真实时刻。

### 1.2 功能需求

**F1 指标 API(YPJ-45,纯读,项目级)**

| 指标组 | 内容 |
|---|---|
| Workload | WIP 按状态分布 × 按 assignee 拆分;backlog 存量 |
| Throughput | 单位时间完成数(周 / sprint 粒度) |
| Cycle / Lead Time | 逐 item 散点数据 + 50/85/95 百分位 |
| CFD | 各状态存量时间序列(由迁移史重放) |
| Aging WIP / Bottleneck | 每 WIP item 进入当前状态的时刻;队列宽度 |

约束:纯 SQL 聚合(必要时物化再议);**对 group type 中性**(不 hardcode sprint,呼应 ItemGroupType 方向);定义口径写进响应(如 cycle = 首次离开 start 状态 → 进入首个 final)。

**F2 图表面板(YPJ-46)**

- 项目级 Flow 页:MUI X Charts(或票内定同生态图库)——WIP 条形(assignee 堆叠)、throughput 直方、cycle/lead 散点+百分位线、CFD 面积图、aging/bottleneck 表(五图一表)。
- Sprint 详情加 burndown(剩余 vs 理想线);sprint 页保持表格+图,无看板(与 YPJ-41 一致)。

### 1.3 验收

- [ ] 各指标端点 + 集成测试(构造迁移史断言数值,含跨 rename 的 id 级正确性)
- [ ] Flow 页五图一表 + burndown 上线;口径说明用户可见

### 1.4 边界

不做:自定义指标公式、跨项目对比、导出;实时推送(等 WebSocket 票)。

---

## 2. 视图配置:保存的筛选器 + 列选择器(V20;YPJ-47 + 28 合并实施)

### 2.1 背景

列表/表格页的筛选条件目前不可保存;列配置 = chip 点击切换,可选列增多后(chip 一排放不下/不可扫描)不可行——触发条件已随 Flow 页列增多成立,与 47 合并为一票避免两次动同一片代码。对标 Plane Views(免费版)。

### 2.2 功能需求

- 筛选器:保存/命名/删除/复用,按 用户×项目 隔离;默认视图概念(是否全局默认另议)。
- 列配置:Popover 多选面板(分组、搜索)+ 列顺序调整;持久化 localStorage 起步(键按项目,沿用 spec 0028),服务端偏好另议。
- 两能力同页管理(「视图」入口)。

### 2.3 验收

- [ ] 筛选可保存/命名/删除/复用;与列配置同页管理
- [ ] 列选择器替代 chip;顺序调整持久化;既有 spec 0028 行为回归

---

## 3. ItemGroup 动态类型(V21;YPJ-48)

### 3.1 背景

epic/sprint/release/milestone 目前是 `item_group.type` 硬编码字符串;产品需要运行时定义分组类型(对标 Plane Work Item Types 付费墙——本仓反手做免费可配置)。需求文档:`docs/requirement/ItemGroup PRD.md` §4/§6,实施计划映射 §28.2 三 Phase。

### 3.2 功能需求

- ItemGroupType 运行时定义(workspace 或项目级,PRD §4 定):类型名、语义标记(时间盒/里程碑/容器等)。
- 新建项目可配置 group 类型;既有 sprint/release 数据迁移。
- 消费端去 hardcode:看板 sprint 语义(spec 0023)、sprint 页、release 关联等改按语义标记/配置读。

### 3.3 验收

- [ ] 新建项目可配置 group 类型;既有数据迁移无丢
- [ ] sprint/release 功能在动态类型下行为不回归;PRD Phase 对应交付

### 3.4 边界

不做:类型级自定义字段(等 Custom Properties 需求);跨类型转换的复杂规则。**开工前单独出实施 spec**(PRD 要求)。

---

## 4. 代码托管平台抽象(V21;YPJ-40)

### 4.1 背景(YPJ-36 合并评审三处未尽)

1. 默认规则种子按 V7 五列模板**状态名**匹配——默认值假设了模板名;
2. 4 槽映射已可配置但默认值代码写死、事件集固定;
3. `GithubGateway` 已是接口但形状是 GitHub 的。

### 4.2 功能需求

- 平台无关 gateway 接口 + adapter 注册(GitHub 首个 adapter;GitLab/Gitee 等真实需求来了再做)。
- 事件集归一(branch_created / pr_opened / pr_merged / pr_closed_unmerged 或超集)。
- 默认状态映射去固化:按语义(首个非 start / 首个 final)或 workspace 级默认模板可配置——二选一票内设计定。

### 4.3 验收

- [ ] gateway 接口无平台专名;默认种子不依赖特定状态名,非标 workflow 项目开箱行为有定义
- [ ] 既有集成测试全绿;新抽象带测试

### 4.4 边界

不做:具体第二平台 adapter;轮询节奏/防呆逻辑改动。

---

## 5. 扩展件(V22)

### 5.1 项目模板与建项向导(YPJ-49)

- 模板 = workflow + 状态 + 属性 + 需求矩阵预设;配置面已全 API 化,本票做**模板存储与应用**。
- 与匹配引擎需求配置结合 = 差异化 onboarding(建项即声明需求,立即得可行性/候选建议)。
- 验收:模板 CRUD + 应用模板建项目端到端。

### 5.2 附件存储(YPJ-50)

- item 级附件上传/下载/删除:附件表 + S3 兼容客户端抽象(presign 上传);驱动选型 RustFS(自托管 S3 兼容)。抽象接口呼应 YPJ-40 平台抽象思路。
- 约束:大小/类型限制;集成测试;敏感与配额策略票内定。

### 5.3 一分支/PR 关联多票(YPJ-39)

- 痛点(dogfood PR #100 实证):票:分支 1:1,多票共用分支时从票 log 断链、PR 关联不可查。
- 要点:branch×item 多对多(`--items` / 补挂端点);PR 关联可查(合并状态/链接);`yz log` 按票可拉共享分支提交;既有 1:1 数据兼容。

### 5.4 GitHub 双向同步(YPJ-51,占坑)

- 现状单向(gh→item);方向:issue↔item 映射、label↔属性映射、`closes #N` 反向关票。
- 工程量大,排 ItemGroup 动态类型之后;**开工前单独出 spec**,范围以届时 spec 为准。

---

## 6. backlog(未排期)

| 票/主题 | 一句话 | 状态 |
|---|---|---|
| YPJ-20 | 富文本 description(Notion/Jira 式层级/引用/转义):PRD + 选型 spike(纯文档票) | backlog,spec 0020 已立 |
| YPJ-38 | service 层 mockk 单测(CI 冒烟) | 已被 YPJ-43 吸收,待关票 |
| Intake | 需求收集入口(与匹配引擎契合) | 未出票 |
| Command K / WebSocket 实时 / 邮件通知 | 交互与协作增强 | 未出票 |
