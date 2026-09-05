-- JCW-95: item 活动日志(变更自动留痕,V3.5-C)
-- 一条变更一行;actor = 登录成员;item 删除级联清日志

create table item_activity (
    id               bigint generated always as identity primary key,
    object_id        uuid        not null,
    item_id          bigint      not null,
    kind             varchar(30) not null,
    old_value        text,
    new_value        text,
    actor_member_id  bigint      not null,
    created_at       timestamptz not null default now(),
    constraint uk_item_activity_object_id unique (object_id),
    constraint fk_item_activity_item      foreign key (item_id) references item (id) on delete cascade,
    constraint fk_item_activity_actor     foreign key (actor_member_id) references member (id),
    constraint ck_item_activity_kind      check (kind in ('created','status_changed','title_changed','description_changed','assigned','unassigned','requirement_changed'))
);

create index idx_item_activity_item on item_activity (item_id, created_at desc);
