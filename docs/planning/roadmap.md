# yangzhou Roadmap:V19–V22(2026-10-14 立版)

> 事实源 = PM 狗粮实例(项目 YPJ)的 sprint 看板;本文件是面向阅读的镜像快照,**排期变更以 PM 为准**。
> 历史规划:`v5-product-plan.md`(V1–V4 盘点 + V5 候选池;候选池大部分已兑现或被后续 sprint 吸收)。
> 需求细节见 `docs/requirement/V20-V22 PRD.md`;V19 伞设计见 `docs/spec/0033-v19-sprint-design.md`。

## 一、现状(2026-10-14)

- **已交付**:V1–V18 全量(YPJ-1~36 Done);V19 首票 YPJ-44 已入(PR #103)。
- 近期新增能力:PR 状态自动同步(spec 0031)、item 级状态迁移审计/Flow 基座(spec 0032)、yz CLI branches 命令、API 访问日志。
- **对标结论**(材料已迁 Obsidian `projects/yangzhou/`:Plane 48 节功能图谱 + kaneo 85 条 user story 评分):Plane 付费墙里的 Workflow / Custom Properties / GitHub 集成,本仓已有或大半有;**高价值主攻 = Flow metrics(双方都缺/浅)与动态类型**。

## 二、Sprint 阶梯

### V19 快赢+地基(active)

| 票 | 内容 | 状态 |
|---|---|---|
| YPJ-44 | Metrics 基座:item 级状态迁移审计(item_status_history + V31 回填) | **Done(#103)** |
| YPJ-41 | Sprint 页面只保留表格视图(看板语义归项目级) | 待开工 |
| YPJ-42 | GitHub 提交历史按需加载(仅分支创建之后) | 待开工 |
| YPJ-37 | 分支自动命名改进(CJK 残迹 + cwjiang/ 前缀) | 待开工 |
| YPJ-43 | 后端单测加固(需求矩阵驱动,**滚动票**,贯穿各 sprint) | 待开工 |

### V20 Flow 价值闭环(依赖 V19 基座)

| 票 | 内容 |
|---|---|
| YPJ-45 | Flow Metrics 只读 API(workload / throughput / cycle/lead / CFD / aging) |
| YPJ-46 | Flow 图表面板:项目 Flow 页五图一表 + sprint burndown |
| YPJ-47 + 28 | 视图配置:保存的筛选器 + 列选择器重设计(合并一票实施) |

### V21 架构收敛

| 票 | 内容 |
|---|---|
| YPJ-48 | ItemGroup 动态类型(ItemGroupType 运行时定义,替代 epic/sprint 硬编码) |
| YPJ-40 | 代码托管平台抽象(gateway 泛化 + 默认状态映射去固化) |

### V22 扩展件

| 票 | 内容 |
|---|---|
| YPJ-49 | 项目模板与建项向导(与匹配引擎需求配置结合 = 差异化 onboarding) |
| YPJ-50 | 附件存储(S3 兼容抽象,驱动 RustFS) |
| YPJ-39 | 一分支/PR 关联多票(branch×item 多对多;dogfood PR #100 实证痛点) |
| YPJ-51 | GitHub 双向同步(issue↔item、label↔属性、closes 联动) |

## 三、backlog 与未出票池

- **backlog 散票**:YPJ-20(富文本 description,纯 PRD+选型 spike 票)、YPJ-38(mockk 单测,已被 43 吸收,待关票)。
- **未出票池**(低优先,触发时再立):Intake 需求收集入口(与匹配引擎契合)、Command K、WebSocket 实时、邮件/IM 通知。

## 四、节奏约定

- 每票 = 一个 YPJ item(dogfood)+ `docs/spec/00NN` 三件套过目后编码;PR 模式;验收全绿才关票。
- sprint 收口:子票全 Done → sprint 组 completed;43 为滚动票,允许跨 sprint 存续。
- 版本 spec 与 As-built:开工前立增量节,完工后补 As-built(0031/0032 为范式)。
