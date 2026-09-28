# ItemGroup PRD

## 1. 产品概述

本产品是一套类似 Jira 的项目管理软件，但核心目标不是固定提供 `Epic / Sprint / Story / Task` 等预定义类型，而是建立一个**运行时可配置的项目管理元模型**。

用户可以根据项目自身的管理方式：

* 自定义 Item 类型
* 自定义 ItemGroup 类型
* 自定义 ItemGroup 的组织方式
* 为不同 ItemGroup 配置不同的 View
* 使用 Milestone 管理项目时间节点
* 使用 Release 管理实际或计划中的交付内容

核心思想：

> **Item 是工作对象，ItemGroup 是组织和定位 Item 的上下文，View 是 ItemGroup 的展示方式，Milestone 是时间/目标节点，Release 是交付上下文。**

---

# 2. 核心设计原则

## 2.1 类型运行时定义

系统不应该将以下概念硬编码成代码中的 enum：

```text
Epic
Sprint
Release
Feature
Task
Bug
```

而应该允许项目在运行时定义：

```text
ItemType
ItemGroupType
ViewType
```

例如：

```text
ItemType:
- Feature
- Task
- Bug
- Story

ItemGroupType:
- Epic
- Sprint
- Release
- Phase
- Initiative

ViewType:
- Table
- Board
- Timeline
- Calendar
```

因此，产品具有高度的可配置性。

---

# 3. 核心领域模型

## 3.1 Item

`Item` 是系统中最基本的工作对象。

例如：

* Feature
* Story
* Task
* Bug
* Improvement
* Spike

Item 的类型由运行时定义的 `ItemType` 决定。

示例：

```text
Item
├── type = Feature
├── title = "支付宝支付"
└── status = In Progress
```

### 基本属性

```text
Item
- id
- type
- title
- description
- status
- assignee
- priority
- createdAt
- updatedAt
```

具体字段可以根据项目配置扩展。

---

# 4. ItemType

`ItemType` 是用户定义的 Item 类型。

系统不应该预设固定的：

```text
Feature
Task
Bug
Story
```

而应该允许项目管理员创建：

```text
ItemType:
- Feature
- Task
- Bug
- Story
- Improvement
- Spike
- 自定义类型
```

例如：

```text
Project A

ItemType:
Feature
Task
Bug
```

Project B 可以定义：

```text
ItemType:
Requirement
WorkItem
Defect
Research
```

---

# 5. ItemGroup

## 5.1 定义

`ItemGroup` 是用于**组织、定位和提供上下文**的一组 Item。

它并不只是传统意义上的“容器”。

其主要价值是：

> 为用户提供一个能够理解和定位 Item 的上下文。

例如：

```text
Epic: Payment
    ├── Feature: 支付宝
    ├── Feature: 微信
    └── Bug: 回调失败
```

或者：

```text
Sprint 42
    ├── Task A
    ├── Feature B
    └── Bug C
```

或者：

```text
Release 2.0
    ├── Feature A
    ├── Feature B
    └── Bug C
```

---

# 6. ItemGroupType

`ItemGroupType` 是运行时定义的 ItemGroup 类型。

系统不应该将：

```text
Epic
Sprint
Release
```

定义为固定的 class 或 enum。

而应该允许：

```text
ItemGroupType
- Epic
- Sprint
- Release
- Phase
- Initiative
- Workstream
- Customer
- 自定义类型
```

例如：

```text
ItemGroupType
├── Epic
├── Sprint
├── Release
└── Phase
```

用户还可以自行增加：

```text
ItemGroupType
├── Initiative
├── Program
├── Customer
└── Milestone Group
```

---

# 7. ItemGroup 的语义

虽然 Epic、Sprint、Release 都可以统一抽象成 `ItemGroup`，但它们表达的业务含义不同。

## 7.1 Epic

主要用于**业务/功能层面的组织**。

例如：

```text
Epic: Payment
    ├── Feature: 支付宝
    ├── Feature: 微信
    └── Feature: Apple Pay
```

用户进入 Epic 时，主要关心：

> 这个业务目标下面有哪些 Item？

因此默认适合：

* Table
* List

---

## 7.2 Sprint

主要用于**执行和时间盒管理**。

例如：

```text
Sprint 42
    ├── Task A
    ├── Task B
    ├── Bug C
    └── Feature D
```

这些 Item 可以来自不同 Epic。

Sprint 主要回答：

> 这一段时间我要做什么？现在做到哪里？

因此默认适合：

* Board
* Table

---

## 7.3 Release

Release 表示一次实际或计划中的**交付/发布集合**。

例如：

```text
Release 2.0
    ├── Feature A
    ├── Feature B
    └── Bug C
```

Release 和 Epic、Sprint 是相互独立的上下文。

一个 Item 可以：

```text
属于 Epic
被安排到 Sprint
被纳入 Release
```

这三种关系表达不同语义。

---

# 8. Release 的特殊设计

Release 与其他 ItemGroup 最大的区别之一，是它可以：

1. 提前规划
2. 在发布过程中持续维护
3. 发布完成后再创建

例如项目一开始没有 Release：

```text
Feature A ✓
Feature B ✓
Bug C ✓
```

发布完成后，可以创建：

```text
Release 2.0
```

并将这些已经完成的 Item 关联进去。

因此：

> Release 不要求必须在 Item 完成之前创建。

---

## 8.1 Planned Release

```text
Release 2.0
Target Date: 2026-10-30
Status: Planned

Items:
- Feature A
- Feature B
- Bug C
```

表示：

> 计划在这个 Release 中交付这些内容。

---

## 8.2 Historical Release

```text
Release 1.9
Released: 2026-09-15
Status: Released

Items:
- Feature X
- Feature Y
- Bug Z
```

表示：

> 这次发布实际上包含这些内容。

---

# 9. Milestone

Milestone 与 Release 分开建模。

Milestone 的核心语义是：

> **项目时间轴上的目标节点或重要进度节点。**

例如：

```text
MVP
Beta
GA
Enterprise Launch
```

Milestone 不应该默认作为 ItemGroup。

---

# 10. Milestone 生命周期

建议 Milestone 支持：

```text
PLANNED
IN_PROGRESS
COMPLETED
CANCELLED
```

典型生命周期：

```text
PLANNED
    ↓
IN_PROGRESS
    ↓
COMPLETED
```

---

# 11. Milestone 的数量

一个项目可以拥有：

```text
0..* Completed
0..1 Current
0..* Future
```

例如：

```text
✓ MVP
✓ Beta
● Release 1.0
○ Release 1.1
○ Release 2.0
○ Enterprise GA
```

其中：

* 历史 Milestone 应保留
* Future Milestone 可以有多个
* Current 通常最多一个
* 项目也可以暂时没有 Current Milestone

因此不建议只设计：

```text
currentMilestone
nextMilestone
```

而应该保存完整的 Milestone 集合。

---

# 12. Milestone 与 Release 的关系

Milestone 和 Release 是两个独立概念。

## Milestone

回答：

> 我们希望什么时候达到什么目标？

## Release

回答：

> 我们实际发布了什么？

两者可以建立关联，但不应该互相替代。

例如：

```text
Milestone: GA
Target: 2026-10-30

Release: 2.0
Released: 2026-10-28
```

也可能：

```text
Milestone: GA
    ├── Release 2.0
    └── Release 2.1
```

具体是一对一还是一对多，需要根据实际业务规则进一步确定。

---

# 13. Item 与 ItemGroup 的关系

虽然 Epic、Sprint、Release 都可以统一抽象为 `ItemGroup`，但业务关系最好不要全部简单命名为 `contains`。

例如：

```text
Epic
    └── organizes Item

Sprint
    └── assigned Item

Release
    └── includes Item
```

因此逻辑上应该区分：

```text
Item ── belongs to ──> Epic

Item ── assigned to ──> Sprint

Item ── included in ──> Release
```

底层可以复用 ItemGroup 模型，但业务语义应该保留。

这样可以避免一个泛化的 `contains` 抹平不同领域关系。

---

# 14. View

View 是用户查看 ItemGroup 中 Item 的方式。

View 与 ItemGroup 解耦。

系统支持多种 `ViewType`。

初始可以包括：

```text
Table
List
Board
Timeline
Calendar
```

未来可以扩展：

```text
Gantt
Dashboard
Roadmap
Matrix
Chart
...
```

---

# 15. ViewType

`ViewType` 是视图类型定义。

例如：

```text
ViewType
├── Table
├── List
├── Board
├── Timeline
└── Calendar
```

ViewType 不应该决定具体数据，而只决定：

> 如何展示数据。

---

# 16. View

具体的 View 是某个 ItemGroup 上的一个实际视图。

例如：

```text
Payment Epic / Table
```

可以配置：

```text
columns:
- Type
- Title
- Status
- Assignee
- Sprint

sort:
- Priority DESC

filter:
- Status != Done
```

而：

```text
Sprint 42 / Board
```

可以配置：

```text
columns:
- Todo
- In Progress
- Review
- Done

cardFields:
- Title
- Assignee
- Priority
```

因此：

```text
ViewType = Table
```

只是模板/能力类型。

真正的：

```text
View
```

包含具体配置。

---

# 17. ItemGroupType 与 ViewType

一个 ItemGroupType 可以支持多个 ViewType。

例如：

```text
Epic
 ├── Table
 ├── Board
 └── Timeline

Sprint
 ├── Board
 └── Table

Release
 ├── Table
 └── Timeline
```

可以建模为：

```text
ItemGroupType "*" -- "*" ViewType
```

即：

> 一个 ItemGroupType 可以支持多个 ViewType，一个 ViewType 也可以被多个 ItemGroupType 使用。

同时可以定义一个默认 View：

```text
Epic    → default = Table
Sprint  → default = Board
Release → default = Table
```

---

# 18. View 与 ItemGroup 的关系

具体的 ItemGroup 可以拥有多个 View。

例如：

```text
Epic: Payment

[Table] [Board] [Timeline]
```

用户可以在多个 View 之间切换。

因此：

```text
ItemGroup
    │
    ├── View: Table
    ├── View: Board
    └── View: Timeline
```

其中一个 View 被标记为默认 View。

---

# 19. 推荐的 UI 导航

左侧导航不应该简单地把所有 ItemGroup 都平铺出来。

推荐：

```text
Project

Overview
All Items
My Items

────────────────
ORGANIZE

Epics
  ├── Payment
  ├── Account
  └── Notification

Sprints
  ├── Sprint 42
  ├── Sprint 43
  └── Sprint 44

Releases
  ├── 1.0
  ├── 1.1
  └── 2.0

────────────────
MILESTONES

MVP
Beta
GA

────────────────
VIEWS

Board
Backlog
Timeline
```

---

# 20. ItemGroup 与导航的关系

不是所有 ItemGroup 都必须显示在左侧导航。

建议 ItemGroupType 支持导航配置，例如：

```text
navigationVisible
navigationOrder
navigationMode
icon
```

例如：

```text
ItemGroupType: Customer

navigationVisible = false
```

表示 Customer 可以存在，但不一定出现在主导航。

也可以：

```text
navigationVisible = true
navigationMode = tree
```

显示：

```text
Customer
├── Acme
├── Google
└── Microsoft
```

这样可以避免“用户定义一个 Group，系统就自动把它塞进左侧菜单”的问题。

---

# 21. 进入不同 Group 的默认体验

## Epic

默认：

```text
Table
```

用户关注：

> 这个业务目标下有哪些 Item？

示例：

```text
Payment

┌──────┬────────────────┬─────────┬──────────┬─────────┐
│ Type │ Item           │ Status  │ Assignee │ Sprint  │
├──────┼────────────────┼─────────┼──────────┼─────────┤
│ F    │ 支付宝支付     │ Done    │ Alice    │ S42     │
│ F    │ 微信支付       │ Doing   │ Bob      │ S43     │
│ T    │ API 接口       │ Todo    │ Alice    │ S43     │
│ B    │ 回调失败       │ Doing   │ Tom      │ S42     │
└──────┴────────────────┴─────────┴──────────┴─────────┘
```

---

## Sprint

默认：

```text
Board
```

用户关注：

> 这一轮要完成什么？目前进行到哪里？

示例：

```text
┌───────────┬────────────┬────────────┬──────────┐
│   TODO    │ IN PROGRESS│   REVIEW   │   DONE   │
├───────────┼────────────┼────────────┼──────────┤
│ Task A    │ Feature B  │ Bug C      │ Task D   │
│ Task E    │ Task F     │            │ Feature G│
└───────────┴────────────┴────────────┴──────────┘
```

---

## Release

默认：

```text
Table / List
```

用户关注：

> 这个 Release 包含哪些交付内容？

可以进一步支持：

```text
Table
Timeline
Board
```

---

## Milestone

默认：

```text
Timeline
```

用户关注：

> 项目当前处于什么阶段，未来有哪些目标节点？

例如：

```text
2026 Q1      Q2          Q3          Q4
  │           │           │           │
  ✓ MVP       ✓ Beta      ● GA        ○ Enterprise
```

---

# 22. 推荐的整体领域模型

```text
                         ItemType
                            │
                           type
                            │
                            ▼
                         Item
                       ▲   ▲   ▲
                       │   │   │
                    Epic  Sprint Release
                       │   │   │
                       └───┴───┘
                           │
                       ItemGroup
                           │
                         type
                           │
                           ▼
                     ItemGroupType
                           │
                        supports
                           │
                           ▼
                        ViewType
                           │
                           ▼
                          View
                           │
                       context
                           │
                           ▼
                       ItemGroup


                       Milestone
                           │
                    time / goal context
                           │
                           ▼
                         Item
```

---

# 23. 核心实体关系

可以抽象为：

```text
Item "*" --> "1" ItemType

ItemGroup "*" --> "1" ItemGroupType

ItemGroup "1" --> "*" Item

ItemGroupType "*" --> "*" ViewType

View "*" --> "1" ItemGroup

View "*" --> "1" ViewType

Project "1" --> "*" Milestone
```

其中 Item 与 ItemGroup 的实际业务关系应该根据 Group Type 保留具体语义：

```text
Epic     → organizes Item
Sprint   → assigned Item
Release  → includes Item
```

---

# 24. 产品核心能力

## P0

### Item 管理

* 创建 Item
* 编辑 Item
* 删除 Item
* 设置 ItemType
* 设置状态
* 设置负责人
* 设置优先级

### ItemGroup

* 创建 ItemGroup
* 设置 ItemGroupType
* 将 Item 关联到 ItemGroup
* 从 ItemGroup 查看关联 Item
* ItemGroup 导航

### 动态类型

* 创建 ItemType
* 创建 ItemGroupType
* 修改类型
* 删除/停用类型

### View

* 支持 Table
* 支持 Board
* 支持 Timeline
* 支持 List
* ItemGroup 配置默认 View
* 用户切换 View

### Sprint

* 创建 Sprint
* 关联 Item
* Board 展示
* Sprint 状态管理

### Epic

* 创建 Epic
* 关联 Item
* Table/List 展示

### Release

* 创建 Release
* 规划 Release
* 关联 Item
* 标记 Released
* 支持发布后创建 Release
* 查看 Release 历史

### Milestone

* 创建 Milestone
* 设置目标日期
* 设置状态
* 支持历史 Milestone
* 支持多个 Future Milestone
* 当前 Milestone 最多一个

---

# 25. P1 能力

### View Configuration

允许用户配置：

* Table 列
* 排序
* Filter
* Group By
* Board Column
* Card Fields
* Timeline 字段
* 默认 View

### ItemGroupType 配置

支持：

* 名称
* Icon
* Color
* Navigation Visible
* Navigation Order
* 支持的 ViewType
* 默认 ViewType

### Milestone 与 Release

支持：

* Milestone 与 Release 关联
* Release 目标日期
* Release 实际发布日期
* Release 历史记录
* Milestone Timeline

---

# 26. 非目标 / 暂不确定事项

以下内容暂不做强约束：

### 26.1 ItemGroup 是否支持无限层级

暂定支持配置化层级，但具体层级规则需要进一步设计。

### 26.2 Sprint 与 Epic 的层级关系

不假设：

```text
Epic > Sprint
```

也不假设：

```text
Sprint > Epic
```

两者属于不同业务维度。

### 26.3 Release 与 Milestone 的基数关系

暂不强制：

```text
1:1
```

或：

```text
1:N
```

需要结合具体产品场景确定。

### 26.4 Milestone 是否可以关联 Item

可以支持，但关系语义应定义为：

```text
tracks / targets / relates to
```

而不是简单的：

```text
contains
```

---

# 27. 设计目标总结

最终产品希望形成以下认知模型：

```text
Item
=
我需要做什么？

Epic
=
这些工作属于哪个业务目标？

Sprint
=
这一轮我要做什么？

Release
=
这次交付包含什么？

Milestone
=
项目现在/未来要达到什么目标？

View
=
我应该用什么方式查看这些工作？
```

因此：

> **Epic、Sprint、Release 可以共享 `ItemGroup` 这个技术抽象，但不共享完全相同的业务语义。**

同时：

> **Milestone 不属于 ItemGroup，而是项目时间/目标轴上的独立概念。**

而：

> **View 不属于某一种固定 Group，而是可以由运行时配置，将不同 ItemGroupType 与不同 ViewType 组合起来。**

最终形成一个可配置的项目管理平台，而不是固定流程的 Jira Clone。
