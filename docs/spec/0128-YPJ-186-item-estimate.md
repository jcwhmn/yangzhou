# spec 0128 — YPJ-186 工作量预估·item 级预估(选档位或自由输入)

> 状态:**已拍板(①A 快照 / ②固定常数 / ③按推荐)并已编码,待用户验证**。接续 spec 0126(项目级刻度已落:V60 / EstimateScales / PATCH settings / 设置面板)。
> 拍板记录(2026-11-15):①方案 A「实际值,不关心档位后改」;②1天=8小时(配置化确认为将来票);③先实现看效果。
> 用户已拍板 4 点(spec 0126 备忘):①item 估计可空,项目可配必须非空 ②刻度档位可增删改 ③每档数值+单位可配(默认五档仅初始占位)④item 可选档位也可自由输入。

## ⚠ 拍板点(领域语义,请过目时拍板)

### 拍板点 ① 存储:item 怎么记预估?(已拍板:A 快照)

| 方案 | 字段 | 选档时 | 档位改动后 | 评 |
|----|----|----|----|----|
| **A 快照(已拍板採用)** | `estimate_value` + `estimate_unit` 两列 | 写入该档的 value+unit | 已有 item **不受影响** | 单一形态(选档/自由输入同构);无悬空引用;换算/排序只需 value+unit |
| B 引用档位 | `estimate_scale_key`(+自由输入时 value+unit) | 写 key | 引用 key 的 item 显示**跟着变**(联动或漂移,两读) | 三字段;档被删要处理悬空;「按哪档估的」可追溯 |

**採用 A**(用户原话:「实际值。如中型,5天,不关心设置中中型已改为7天这样的事实」)。代价:「按哪档估的」不可追溯——将来需要可在 A 基础上加来源标记列,零损迁移。

### 拍板点 ② 换算常数(已拍板:固定 8h/day、40h/week)

- **採用固定 1 day = 8h,1 week = 40h**(用户确认,配置化意向留将来)——仅作为**前端表格排序**的规范化 key(混合单位时 8h 排在 2day 前);常量收敛在 `web/lib/estimate.ts` 一处,将来配置化只改此文件+加 settings 字段。
- 后端本票不落换算函数(无汇总消费;父子汇总/实绩对比仍非目标)。

### 拍板点 ③ 展示与列默认(已拍板:按推荐实现,看效果再调)

- 显示格式:**「5天」「8小时」**(数值去尾零+单位中文),**不显示档名**(改档不漂移;档名反查会撞同值多档)。
- 表格「预估」列:进 ColumnConfig 列清单,**默认显示**(visible: true,可手动关)。
- 看板卡片角标:dueDate 旁同款式文本(如「5天」),无预估不占位。

## 拍板后的两处设计修订(实况与 spec 原案的偏差,显要标注)

1. **立票入口 = 顶栏快速添加,非「与详情共用一段控件」**:实况是顶栏一行式极简表单(标题+提交),且立票按 S0–S3 场景分流(S0 直建/S1 直挂组/S1m 选组/S2·S3 确认框)。实现:EstimateField 进顶栏表单行,预估值穿全部分流路径(直传或 pendingEst 快照,同 pendingTitle 式);详情页编辑区同样有完整控件(档位+自定义+清除)。required 项目若预选空档,前端禁提交+提示,后端 400 兕底。
2. **CLI 无 items update 命令**(只有 create/list/show/comments/assign/move/branch):`--estimate` 落 `items create` + `items show` 显示;编辑走 web。若将来补 CLI update 命令再带 --estimate,另票。

## 一、需求

### 范围

1. **item estimate 字段(可空)**:value(Double,>0≤999)+ unit(封闭枚举 hour/day/week,与 180 一致);选档位=写入该档 value+unit 快照(拍板点①方案 A),自由输入=直接写 value+unit。
2. **required 强制**:项目 `estimateRequired=true` 时,立票(POST)无预估 → 400;编辑显式清空 → 400。本票接通 180 落库开关的写入侧消费。
3. **立票/编辑对话框**:预估输入控件——档位下拉(显示 `label(value+单位)`,如「中(5天)」)+「自定义」项(数值输入+单位下拉)。
4. **表格「预估」列**(table 视图,ColumnConfig 可配)+ **看板角标**(主页面/看板卡片)。
5. **CLI**:`items create` / `items update` 加 `--estimate`,`items show` 显示预估。
6. 词汇表增补:CONTEXT.md 加「工作量预估(Estimate)」词条(value+unit 快照语义)。

### 非目标

- 父子汇总、项目/人维度的预估统计、实绩对比(工时 report 对预估)、匹配引擎联动。
- Excel 导出加预估列(未要求)。
- 后端换算/汇总函数(拍板点②,将来票)。
- 项目刻度配置本身(180 已完成)。

## 二、详细设计

### 数据(V61 迁移)

```sql
ALTER TABLE item ADD COLUMN estimate_value DOUBLE PRECISION NULL;
ALTER TABLE item ADD COLUMN estimate_unit VARCHAR(8) NULL CHECK (estimate_unit IN ('hour','day','week'));
```

- unit 是**封闭枚举**(180 拍板),按仓纪律加 CHECK;value 范围 >0≤999 走 service(与刻度档一致,不落 CHECK)。
- 两列**成对**有值或成对 NULL(部分置值由 service 拒绝,不落库级约束)。

### 后端(api/business/item)

- `Item` 实体 + `estimateValue: Double? = null`、`estimateUnit: String? = null`。
- ItemResponse + 同名字段(可空)。
- **输入三态**(复用 priority/日期的仓惯例):`estimateValue: String?` + `estimateUnit: String?`——null=不改;空串=清除(成对);文本=设置(成对,value 可解析为数字且 >0≤999,unit ∈ hour/day/week;**单给其一 → 400**)。
  - 否决的备选:嵌套对象 `estimate: {value, unit}?`——三态(null=不改/显式清空)无法在单字段表达,且偏离仓内扁平三态惯例。
- 校验函数 `EstimateInput`(item slice 内小 object):解析+校验,返回 `(Double, String)` 对;错误信息走统一 BadRequestException。
- `ItemService.create`:解析 request 两字段 → 写实体;**项目 estimateRequired=true 且解析结果为空 → 400**(错误信息指明「本项目立票必须填预估」)。
- `ItemService.update`:三态合并(同 priority 写法 if-not-null + 空串清除);**显式清除且 required=true → 400**;变更落 activity log(`estimate_changed`,old/new 格式「5天」)。
- 复用 `EstimateScales.UNITS` 常量(project slice 公开常量,item slice 引用;不复制枚举)。
- api.http:items 区块补 create/update 带 estimate(档位值与自定义各一);负例区补 3 请求:required 强制 400 / unit 非法 400 / 单给 value 不给 unit 400。
- entities.puml:Item 加两字段。

### web

- `lib/types`+api:Item 类型加 `estimateValue/estimateUnit`;**换算常量** `HOURS_PER_DAY=8, HOURS_PER_WEEK=40` + `estimateHours(value,unit)` 纯函数(表格排序用,拍板点②)+ 单位显示名映射(hour→小时/day→天/week→周,texts.ts 文案外置)。
- **输入控件**(立票快速添加 + item 详情编辑区共用一段):MUI Select——选项=项目刻度各档(显示 `label(value+单位)`,数据取项目 settings 的 estimateScales;未配置时显示默认五档)+ 固定项「自定义」;选自定义展开数值 TextField+单位 Select;已有值时旁挂清除按钮(required 项目禁清除,行内提示「本项目必须填预估」)。
- 表格页:列清单加 `{ id: "estimate", label: "预估" }`(默认显示);显示「5天」;sortAccessor 用 `estimateHours`(空值排最后,与现有空值排序行为一致)。
- 主页面(看板)卡片:dueDate 行旁追加预估文本,无则不渲染(与 dueDate 同 Typography 款式)。
- item 详情页:头部属性区显示预估;编辑区含输入控件,提交走 PATCH 两字段(空串=清除语义)。

#### 前端操作流程(状态×动作真值表)

| 表单状态 | 动作 | 结果 |
|----|----|----|
| 打开表单(项目有刻度) | 初次渲染 | 下拉=各档+自定义;无预选(item 无预估时) |
| 打开表单(item 已有预估) | 初次渲染 | 命中同值档位→选中之;无命中→落在「自定义」并回填数值+单位 |
| 选某档 | 提交 | 落该档 value+unit 快照 |
| 选「自定义」 | 展开 | 数值输入+单位下拉(hour/day/week 中文);数值≤0 或空 → 保存禁用+行内错误 |
| 已有预估 | 点清除 | estimateValue/estimateUnit 交空串;required=true 时按钮禁用+提示 |
| required=true 且预估空 | 提交 | 顶栏快速添加:保存禁用+提示「本项目立票必须填预估」;详情编辑:放行(后端对已空再清=同值跳过,不误伤其它字段保存) |
| required=true 已有预估 | 选「—」 | 前端禁选该选项;后端 400 兕底「不能清除」 |
| required=true 已空再清 | 提交 | 同值跳过=无变化,200(不拦;required 只禁「从有到无」) |
| 表格列 | 排序 | 按规范化小时(8h/day、40h/week),空值最后 |
| 卡片 | 渲染 | 有预估显示「N单位」,无则不占位;有截止时同行「5天 · 日期」 |

#### 示意图

```
对话框预估区:
┌ 预估 ────────────────────────────────┐
│ [中(5天) ▼]  (清除)                  │
│  ├ 微(2天)                            │
│  ├ 小(3天)                            │
│  ├ 中(5天)        ← 选档              │
│  ├ 大(8天)                            │
│  ├ 超大(13天)                         │
│  └ 自定义   → [ 14 ] [小时 ▼]         │
└───────────────────────────────────────┘

表格列(默认显示):           看板卡片角标:
│ 预估 │                      ┌──────────────┐
│ 5天  │                      │ YPJ-186 标题  │
│ 8小时│                      │ 5天 · 11-20   │ ← 预估+截止同行
│ (空) │                      └──────────────┘
```

### CLI(Main.kt)

- `items create` / `items update` 加 `--estimate <v>`:解析 `5h` / `2d` / `1w`(数字+单位后缀,大小写不敏感)或档位 key(`--estimate m` → 查项目刻度取该档 value+unit;查无此档 → 报错)。落 API 两字段。
- `items show`:行内追加 `预估:5天`(与 priority 同行或单独行,编码时对齐现有版式)。

### 测试(后端;先读 backend-testing skill)

- 单元:EstimateInput 解析/校验(合法、非法 unit、value=0、单给其一);update 三态合并(null 不改/空串清除/值设置);required 强制(create 缺失 400、update 显式清除 400、required=false 不拦)。
- 集成(extends AbstractApiTest):create 带 estimate 往返;PATCH 三态;required 项目立票缺预估 400;非法 unit 400(DB CHECK 兜底不脏库)。
- 表 cleanDatabase 清单按 skill 补 item 相关已有表(无新表,预计零新增;V61 只是加列)。

### 否决的备选(汇总)

- ~~存档位 key 引用(方案 B)~~:改档翻动历史+悬空,拍板点①推荐 A。
- ~~自由文本「5小时」解析~~:中文单位解析脆弱;扁平两字段+封闭枚举最简。
- ~~嵌套 estimate 对象~~:三态语义表达不了,见上。
- ~~后端换算列(DTO 带规范化小时)~~:排序纯前端行为,DTO 加派生字段是投机。

## 三、手工测试用例(用户 build/debug 阶段执行)

1. 默认项目(刻度未配置):立票对话框下拉显示默认五档+自定义;选「中」提交 → 详情/表格/卡片显示「5天」。
2. 自由输入:选自定义,填 14+小时 → 显示「14小时」;表格按预估排序 8小时 < 1天(8h) < 2天。
3. 改档后不漂移:项目把「中」改为 6天 → 已有 item 仍显示 5天。
4. required 关→开:项目开「立票必须填预估」;立票不填 → 对话框行内报错,api.http 直打 → 400;填了 → 过;编辑点清除 → 禁用。
5. required 关:清除预估(对话框清除按钮)→ 置空成功,各视图不显示。
6. CLI:`items create <KEY> <标题> --estimate 2d` → show 显示「2天」;`--estimate m` → 显示该档值;`--estimate 0h` → 报错。
7. 负例(api.http):unit=month → 400;只给 value 不给 unit → 400。

## 四、As-built(2026-11-15 编码完成,待用户验证)

### 落点

- V61__item_estimate.sql:item + estimate_value DOUBLE PRECISION / estimate_unit VARCHAR(8) CHECK(hour/day/week);item_activity 的 ck_item_activity_kind 白名单扩 estimate_changed(#25 同 V16/V40 惯例)
- GlobalExceptionHandler.integrity:+ log.warn 带栈(原吞栈不记日志,409 无从归因;台账 #25,归因基建配套)
- Entities.Item:estimateValue/estimateUnit;entities.puml 同步
- EstimateInput.kt(ItemService.kt 内 object)+ applyEstimate/estimateText 内部纯函数;ItemDto/ItemResponse + 两字段;toDto 映射
- ItemService.create:解析先于取号锁(fail fast)+ required 强制;update:三态合并(同值跳过在前,required 只禁「从有到无」)+ estimate_changed 活动日志
- CreateItemRequest/UpdateItemRequest + estimateValue/estimateUnit(String? 三态);ItemController 透传
- api.http:item 区预估设置/清除两请求;负例区 3 请求(单给其一/unit 非法/value=0)
- web:lib/estimate.ts(EstimateScale 类型 + estimateText/estimateHours,换算常数收敛此一处);texts.ts t.estimate 节;EstimateField.tsx(档位下拉+自定义+清除,required 禁「—」选项);主页面(顶栏表单入控件,est 穿 S0/S1/S1m/S2·S3 全路径,卡片角标同行「5天 · 日期」);详情页(编辑区控件+saveBasics 两字段,estInvalid 禁保存);表格页(列默认显示,getter 用规范化小时,单元格 estimateText)
- CLI:create --estimate(5h/2d/1w 或刻度 key/label)+ show 预估行
- 测试:单测 EstimateInputTest 10 例(三态/成对/非法/同值跳过/required 边界/文本格式)——分支与业务规则全在单测;集成 ItemEstimateApiTest 2 例仅关键路径(PATCH 设置/不变/清除 HTTP 契约读回 + required 立票/清除强制),非法输入分支不重复进集成(用户分层口径:单测盖分支,集成只测关键路径)
- 词汇表:CONTEXT.md 加 Estimate 词条(快照语义)

### 验证(用户执行)

- backend:`gradle build`(单元)→ `gradle integrationTest --tests "yangzhou.api.business.item.ItemEstimateApiTest"`(单类快反馈)→ merge 前 `gradle integrationTest` 全量
- web:`npm run format` + `npx tsc --noEmit` + `npx next build`;手工用例 §三 1–6
- CLI:fat-jar 重建后 `items create <KEY> <标题> --estimate 2d` / `--estimate m` / show
- e2e smoke(表单结构变了,跑一遍确认定位器没破):`npx playwright test --grep "@smoke"`
