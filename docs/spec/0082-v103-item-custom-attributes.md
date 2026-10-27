# spec 0082 — V25 YPJ-103 item 自定义属性 + 可搜索

> 票:YPJ-103。约束:必须先厘清与 ADR-0003 需求矩阵属性的关系,防两套体系(票面要求)。

## ⚠ 与 ADR-0003 的关系裁定(置顶,票面核心问题)

**item 自定义属性 = 同一 `attribute_definition` 词表的第三种挂载,不是第二套体系。**

- ADR-0003:一个 Attribute 实体,`kind` 细分(label/skill/…)纯 data,新增属性类型 = 加数据不加代码路径。本票照此办理:新增 **kind `custom`**(加数据)+ 新挂载侧 **item_attribute**(加一张交叉值表),词表 CRUD/唯一性/分类体系全部复用,零新词表体系。
- 与既有两种挂载的分工:**Requirement(item 侧,min-level,匹配输入)**、**Capability(member 侧,level,匹配输入)**、**ItemAttribute(item 侧,自由文本值,描述性,永不进匹配引擎)**——领域规则 5:角色/描述不进匹配,本票不碰 domain 模块。
- kind 语义:custom = 描述性字段;skill/label 保持匹配专属,挂载时校验 kind=custom(防匹配词表混入描述字段,词表 UI 可分区)。

## 一、需求

### 范围
- 词表支持 `custom` 类(CHECK 放开 + validateKind 白名单 + yz `attrs create --kind custom`)。
- item 挂载:整表替换端点 `PUT /items/{itemId}/attributes`(仿 requirements 整表替换);ItemDto 回带 attributes(attributeId/name/value)。
- **搜索**:全局搜索命中属性值(SearchRepository SQL 加 EXISTS 子查询,一处改动)。
- web:详情页自定义属性区块(显示 + 增删改,保存走整表 PUT)。
- 值统一 `varchar(500)` 文本——**不做值类型系统**(数字/日期/选项按第一个真实需求出现再演进,同 spec 0056 否决「类型行为配置」的逻辑)。

### 非目标
- 后端列表过滤参数:现有 item 列表是全量返回 + 前端过滤(无任何字段级后端过滤先例),属性过滤由 ItemDto 携带数据 + 前端消费,后端不为本票单开先例。
- **表格属性列与属性筛选 UI**(依赖列系统从固定集合动态化,牵动列配置持久化 schema)→ 记后续票,见拍板点 3。
- 值类型/多值/单位;属性级权限(custom 词表管理属 workspace 词表,本就不设门槛,与 YPJ-102 一致)。
- CLI 挂属性命令(items attr set)——记账不实现。

## 二、详细设计(代码级)

### 1. 迁移 `V40__item_attribute.sql`
```sql
alter table attribute_definition drop constraint ck_attribute_definition_kind;
alter table attribute_definition add constraint ck_attribute_definition_kind
    check (kind in ('skill', 'label', 'category', 'custom'));

create table item_attribute (
    id                      bigint generated always as identity primary key,
    object_id               uuid         not null,
    item_id                 bigint       not null,
    attribute_definition_id bigint       not null,
    value                   varchar(500) not null,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now(),
    constraint uk_item_attribute_object_id unique (object_id),
    constraint uk_item_attribute_item_attr unique (item_id, attribute_definition_id),
    constraint fk_item_attribute_item foreign key (item_id) references item (id) on delete cascade,
    constraint fk_item_attribute_attr foreign key (attribute_definition_id) references attribute_definition (id)
);
```
- 与 requirement/capability 同构(交叉挂载表,无审计列,按 V34「纯交叉表按需」惯例)。

### 2. attribute 切片(小改)
- `validateKind` 白名单加 `custom`。
- `AttributeService.delete` 的 409 消息改「属性仍被能力、需求或 item 属性引用」(DataIntegrityViolation 天然覆盖新 FK,仅文字)。

### 3. item 切片:挂载
- DTO:`ItemAttributeDto(attributeId: UUID, name: String, value: String)`;`ItemDto` 加 `attributes` 字段(get/list 回带)。
- `PUT /items/{itemId}/attributes` body `{"values": [{"attribute": "<词表 objectId>", "value": "..."}]}` → 整表替换,返回 ItemDto。校验:词表存在(404)且 kind=custom(400)、值非空 ≤500(400)、同词表重复(400)。
- 不做建 item 时挂载(requirements 有建时挂载先例,但本票保持端点最小,需要时后补)。

### 4. 搜索:`SearchRepository.search` SQL
```sql
WHERE (... 现有三条件 ...)
   OR EXISTS (SELECT 1 FROM item_attribute ia
              WHERE ia.item_id = i.id AND ia.value ILIKE :q ESCAPE '\')
```
- 不按 kind 过滤(custom 才能挂,自然限定); LIMIT 50/ORDER 不变。

### 5. web:详情页属性区块
- 详情页新增「属性」页签或区块(随详情页既有 tab 结构),行 = 词表选择(仅 custom 类)+ 值输入;保存 = 整表 PUT。
- 搜索框零改动(结果多了属性命中,自然混入)。

### 6. 测试(按 backend-testing skill 双轨)
- **新表** `item_attribute` 加进 AbstractApiTest cleanDatabase 清单(requirement 旁,子表先删)。
- 单测(ItemService):非 custom 400 / 词表不存在 404 / 值空 400 / 超 500 400 / 同词表重复 400 / 整表替换+DTO 回带。
- 集成:挂载链(建 custom 词表 → PUT → 详情回读 → 替换 → 清空)、搜索命中属性值、非 custom 拒挂 400、删被引用词表 409。
- e2e:倾向不加(UI 简单渲染,后端集成已覆盖)。

## 三、手工测试用例(供 build/debug,8081)
1. 词表建两个 custom 类(如「模块」「交付物链接」);kind 下拉/CLI 可选 custom。
2. item 详情挂两行属性 → 保存 → 回显正确。
3. 同词表挂第二行 → 前端拦/后端 400。
4. 全局搜索输入属性值(如链接片段)→ item 命中。
5. skill 词表尝试挂 → 400。
6. 删被引用的 custom 词表 → 409;清掉 item 上引用后再删 → 成功。

## 需用户开工前拍板
1. **挂载限 kind=custom**(skill/label 保持匹配专属)——推荐限,UI 分区清晰。
2. **值全 text varchar(500)**,不做值类型系统——推荐最薄。
3. **表格属性列 + 属性筛选 UI 记后续票**(列系统需动态化,牵动持久化 schema)——本票「可过滤」= 数据可达(DTO 带全)+ 前端既有过滤机制可消费;表格 UI 延后。接受拆分吗?
4. 详情页入口用**独立 tab**还是塞「概要」区块——看详情页 tab 布局,默认独立 tab。

## 四、As-built(2026-10-27 实现完成,三绿;手工用例待用户跑)

- 迁移 V40:kind CHECK 加 custom;item_attribute 表(**带 created_by/updated_by 审计列——与 Requirement 现状同构,修正 spec「无审计列」的判读**;FK 级联删 item;idx item);item_activity kind CHECK 一并加 attribute_changed(留痕惯例)。
- 实体/Repo:ItemAttribute(@Table item_attribute)+ ItemAttributeRepository(findByItemId/In/deleteByItemId,与 RequirementRepository 同构)。
- attribute 切片:validateKind 白名单 + delete 409 文字更新。
- item 切片:ItemAttributeDto(attribute 按名字,与 RequirementDto 同风格)/ItemDto.attributes(默认空);PUT /items/{id}/attributes 整表替换(校验:custom-only 400/词表 404/值空与>500 400/同词表重复 400);get 单查回带、list 批量回带(findByItemIdIn+findAllById,无 N+1);留痕 kind=attribute_changed。create/update 返回的 attributes 恒空(文档已注明,前端保存后 load())。
- 搜索:SearchRepository SQL 加 EXISTS 子查询命中属性值。
- api.http:§3 建 custom 属性(attrCustom 变量)→ §7 正例 → 负例区 2 条(非 custom 400/重复 400)。
- web:详情页加「属性」tab(detailTabs 数组尾);AttrEditor 组件(词表下拉仅 custom + 值输入 + 删行 + 保存整表 PUT,保存后 load 刷新);activityLabel 加属性变更;搜索零改动。
- yz CLI:attrs create --kind 帮助文本加 custom(逻辑透传,custom 默认不分级)。
- 测试:单测 ReplaceAttributes 组 6 例(挂载留痕/清空/非 custom 400/词表 404/重复 400/空值与超长 400)+ 既有单测补 itemAttributes mock;集成 ItemAttributeApiTest 3 例(挂载链回读/搜索命中/约束组:非 custom 400、404、重复 400、删被引用词表 409→清引用后 204)。cleanDatabase 加 item_attribute。
- 验证:gradle build 绿;integrationTest --rerun-tasks 全量真跑绿(3m41s);web next build 绿、tsc 无本票错误(main 上 e2e/release.spec.ts 3 个既有 TS 错,与本票无关)。
- 过程插曲:entities.kt 插入 ItemAttribute 时吃掉了 Requirement 的 @Table("requirement") 注解(插点在注解与类之间),集成测试抓出 BadSqlGrammar 后修复——新实体必须自带 @Table,勿借位上一实体的注解。
