-- YPJ-44:Metrics 基座——item 级状态迁移审计(spec 0032)
-- 任意路径(create / 手动 move / GitHub 管道)的状态迁移留 item 级行;V20 Flow metrics 供数。
-- 存量回填:从 item_activity 可解析部分回填;名字解析不到(改名漂移)即丢弃。

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
    constraint fk_item_status_history_item   foreign key (item_id)        references item (id)   on delete cascade,
    constraint fk_item_status_history_from   foreign key (from_status_id) references status (id),
    constraint fk_item_status_history_to     foreign key (to_status_id)   references status (id),
    constraint fk_item_status_history_actor  foreign key (actor_member_id) references member (id),
    constraint ck_item_status_history_source check (source in ('created', 'manual', 'github'))
);

create index idx_item_status_history_item on item_status_history (item_id, created_at);

-- 回填:status_changed(手动)全信;github_status_changed 取 new_value 首个 '(' 前缀为目标名,
-- 'WIP 满跳过' 留痕非迁移,排除;状态名含 '(' 的历史行解析不出即落选(边界,票面"算不出即丢弃")。
insert into item_status_history (object_id, item_id, from_status_id, to_status_id, source, actor_member_id, created_at)
select gen_random_uuid(), a.item_id, f.id, t.id,
       case a.kind when 'github_status_changed' then 'github' else 'manual' end,
       a.actor_member_id, a.created_at
from item_activity a
join item i on i.id = a.item_id
join status t on t.project_id = i.project_id and t.name = btrim(split_part(a.new_value, '(', 1))
join status f on f.project_id = i.project_id and f.name = a.old_value
where a.kind in ('status_changed', 'github_status_changed')
  and a.new_value is not null and a.old_value is not null
  and a.new_value not like 'WIP 满跳过%';
