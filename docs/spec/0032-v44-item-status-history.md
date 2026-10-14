# 0032 YPJ-44:Metrics 基座——item 级状态迁移审计

> 票:YPJ-44(Flow Metrics 第 1 票,V20 全依赖)。分支 `cwjiang/YPJ-44-status-history`。
> 调查结论已修正票面一处假设,见 §需求。

## 需求(票面 + 调查修正)

### 现状(调查实证)

- `status_transition`(V2)是**合法迁移配置表**(workflow rule,`unique(from,to)` 每对一行),无 item 概念——票面「StatusTransition 加 item_id」选项与此表语义冲突,**否决**(见详细设计§否决备选)。
- `item_activity`(V9,扩于 V13/V14/V16)是唯一 item 级流水:kind `status_changed`(手动迁移,old/new 存**状态名字符串**)与 `github_status_changed`(YPJ-36 管道,new 存 `"目标名(event detail)"` 拼接,WIP 满跳过时存 `"WIP 满跳过:…"` 非迁移留痕);仅覆盖 2/3 写入路径,且改名后历史名字漂移。
- **item 状态写入点全仓仅 3 处**(grep `statusObjectId =` 实证):
  1. `ItemService.create`(~L115)——初始状态(显式 statusItemId,否则 isStart 最小 position 兜底);
  2. `ItemService.update`(~L251)——手动迁移(assertTransitionAllowed → WIP limit 校验 → save → activity);
  3. `GithubSyncService.applyEvent`(~L134)——管道迁移(无规则忽略 / final 封口 / 只按 position 前进 / WIP 满跳过留痕)。
- **批量迁移路径不存在**(仅 ItemController 接 statusItemId);**回收站 restore 只清 `deleted_at`,不改状态**——两者均非迁移路径,票面「清点」在此收口。

### 范围

新表 `item_status_history`:任意路径的状态迁移留 item 级审计行(from 可空 = 初始进入),存量从 `item_activity` 可解析部分回填,算不出即丢弃。为 V20 Flow metrics(Cycle/Lead、CFD)供数。

### 非目标

- 不做任何读端点/聚合查询(V20 定查询形态再加);
- 不改 `status_transition` 配置表语义;
- 不改 `item_activity` 既有行为(继续记,双轨并存);
- 不动 restore/软删除(不产生迁移行);不做 domain 引擎改动。

## 详细设计

### 1. Flyway `V31__item_status_history.sql`

```sql
create table item_status_history (
    id               bigint generated always as identity primary key,
    object_id        uuid        not null,
    item_id          bigint      not null,
    from_status_id   bigint,                -- null = 初始进入(create)
    to_status_id     bigint      not null,
    source           varchar(20) not null,
    actor_member_id  bigint,                -- null = 系统事件(GitHub),沿 V13 先例
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint uk_item_status_history_object_id unique (object_id),
    constraint fk_item_status_history_item  foreign key (item_id)        references item (id)   on delete cascade,
    constraint fk_item_status_history_from  foreign key (from_status_id) references status (id),
    constraint fk_item_status_history_to    foreign key (to_status_id)   references status (id),
    constraint ck_item_status_history_source check (source in ('created','manual','github'))
);
create index idx_item_status_history_item on item_status_history (item_id, created_at);
```

- 命名/约束风格照 `item_activity`(V9):identity 主键 + object_id 唯一 + 命名 FK/CHECK;内部 bigint FK 有 `item_activity.item_id` 先例,对外(V20)再走 UUID。
- `created_at` 用 DB default,service 不读时间 → 本票无需 Clock 注入(skill 约定「凡代码读时间」才注入)。

**存量回填**(同文件,`insert … select` 照 V30 范式):

```sql
insert into item_status_history (object_id, item_id, from_status_id, to_status_id, source, actor_member_id, created_at)
select gen_random_uuid(), a.item_id,
       f.id, t.id,
       case a.kind when 'github_status_changed' then 'github' else 'manual' end,
       a.actor_member_id, a.created_at
from item_activity a
join item i on i.id = a.item_id
join status t on t.project_id = i.project_id and t.name = btrim(split_part(a.new_value, '(', 1))
left join status f on f.project_id = i.project_id and f.name = a.old_value
where a.kind in ('status_changed', 'github_status_changed')
  and a.new_value not like 'WIP 满跳过%'   -- 非迁移留痕,排除
  and a.new_value is not null and a.old_value is not null;
```

- **算不出即丢弃边界**(票面要求):名字在当前 status 表解析不到(改名漂移/已删状态)→ join 不上自然落选;`github_status_changed` 目标名取 `new_value` 首个 `(` 前缀(状态名含 `(` 即丢,当前默认三态无此形状,边界注释在案)。
- on conflict 无需(V30 教训:object_id 每行 gen_random_uuid 必不冲突,迁移只跑一次)。

### 2. persistence:`entities.kt` + `repositories.kt`

- `ItemStatusHistory` entity(主构造声明 id/objectId/createdAt/updatedAt,照 ItemActivity 形状);
- `ItemStatusHistoryRepository`:`findByItemIdOrderByCreatedAtAsc`(V20 备用,本票仅 save 用)。

### 3. api 写入点(3 处,直 save 不加抽象层)

| 文件 | 位置 | 写法 |
|---|---|---|
| `ItemService.kt` | `create` ~L115 后 | `(from=null, to=status.id, source='created', actor=creator)` |
| `ItemService.kt` | `update` 状态分支 ~L251 | **加一行守卫 `status.objectId != current.statusObjectId` 才写行**:`(from=oldStatus.id, to=status.id, source='manual', actor)`;同状态 no-op 不产生零时长行(activity 照旧,不动) |
| `GithubSyncService.kt` | `applyEvent` save 后 ~L134 | `(from=current?.id, to=target.id, source='github', actor=null)`;各早退分支(无规则/封口/回退/WIP 跳过)天然不写 |

两 service 各注入 `ItemStatusHistoryRepository`。

### 4. 连带动词

- `AbstractApiTest.cleanDatabase` 清单加 `"item_status_history"`(挨着 `item_activity`,同为 item/status 子表);
- `docs/architecture/entities.puml` 加 `item_status_history`(schema 变更强制同步);
- 无新端点 → `api.http` 不动;domain 引擎零改动 → `domain.puml` 不动。

### 否决的备选

- **StatusTransition 加 item_id**:该表是配置表(unique from,to 每对一行),塞审计行违反其语义,直接否决;
- **只扩 item_activity**(加 status id 列):旧行仍是名字字符串,回填后新旧行形状不一,查询双轨;且其 kind 枚举继续膨胀;
- **事件溯源式统一写入口**(收敛到一个 TransitionRecorder 组件):3 个写入点、每处 1 行,抽象无收益,YAGNI;
- **回填不做**:V20 上线即「历史洞」, Cycle/Lead 对存量失真;可解析部分回填成本低。

## 测试(集成,extends AbstractApiTest;新增 `ItemStatusHistoryApiTest`)

一个测试类驱动 3 条路径 + 守卫,断言响应体 + `jdbc` 直查新表(skill 允许):

1. **create**:`createItem` → 表 1 行(from NULL,to=初始状态,source=created);
2. **manual**:`PUT /api/items/{id}` 改 statusItemId → +1 行(manual,from/to 正确);**同状态再 PUT 一次** → 行数不变(no-op 守卫);
3. **github**(照 GithubSyncApiTest 先例,fake gateway + 注入 syncService):项目挂 repo(默认 3 槽已种)+ item 初始 → PR open → sync → +1 行(github,In Progress→In Review);置 merged → sync → +1 行(→Done);**再 sync 一轮** → 行数不变(幂等封口);
4. **WIP 满跳过不写行**:目标状态设 wip_limit=1,塞满后触发管道 → 行数不变(仅 activity 留痕);
5. **回填不做集成测试**:测试库总是新库,迁移时无存量可回填——回填验证走手工(下节),与 V30 同判例。

## 手工测试用例(build/debug 阶段)

1. `gradle :api:bootRun`(default profile,PM 库 yangzhou 有真实存量 activity)→ 启动后 psql:
   `select count(*) from item_status_history;` > 0,且
   `select source, count(*) from item_status_history group by 1;` 里 manual/github 均有(YPJ dogfood 两条路径都真实发生过);
2. 抽查一条 2026-10 中旬的 item(如 YPJ-36 #102):history 行序列与该 item 实际走过的状态一致(In Progress→In Review→Done),github 行 actor 为空;
3. web 前端把一个 item 拖看板换状态(走 PUT update)→ history +1 行 manual;
4. `gradle build --no-daemon` 绿。

## 验收对照(票)

- [ ] 任意路径的状态迁移都留 item 级审计 ← 3 写入点全覆盖(批量/restore 经查不存在/不迁状态)
- [ ] 集成测试覆盖各路径 ← §测试 1–4
- [ ] (票面)存量回填 ← V31 insert-select,手工用例 1–2 验证
- [ ] (票面)group type 中性 ← 表结构无 group/sprint 概念,纯 item×status,天然中性


## As-built

实现与 spec 一致,补记三处实现期细节:

- **回填 from 解析改 inner join**:spec 草稿 `left join status f` 会把「old 名解析不到」的行落成 from=NULL(误作初始进入),与票面「算不出即丢弃」不一致——实现改为与 to 同样的 inner join,双向解析不到均落选。
- **repositories.kt 补 import**(ItemStatusHistory),spec 文件清单未列,编译期发现。
- **toStatusId 用 `status.id!!` / `target.id!!`**:entity `id` 声明为 `Long?`,spec 伪码未体现非空处理。

验证:全量 integrationTest **148 tests, 0 failures**(含新增 ItemStatusHistoryApiTest 3 例);`gradle clean build` 绿;bootRun(test profile)V31 落位;PM 实例(yangzhou 库)应用后回填行 created_at = 原 item_activity 迁移时刻(2026-10-06 起历史保留,用户库上实证)。无 REST 契约变更(api.http 未动);entities.puml 已同步(既有漂移:ItemActivity/Release 自 V9/V29 起缺席,未顺手扩,另票)。
