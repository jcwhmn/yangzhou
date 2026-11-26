-- YPJ-269(spec 0174):组状态史——ItemGroup 生命周期侧栏数据基座。
-- 每次组状态变更一行(from null=初始进入;source=created/action/cascade,SRS §3.1)。
-- 存量组不回填(迁移时刻无处可考,UI 显示"—";对齐 V31 item_status_history「算不出即不造」先例)。
-- 提取值(started/completed)零物化:读时从本表派生(镜像 item LifecycleTimes 先例,item 表无对应列)。

create table group_status_history (
    id                          bigint generated always as identity primary key,
    object_id                   uuid        not null,
    group_object_id             uuid        not null,
    from_group_status_object_id uuid,       -- null = 初始进入(create)
    to_group_status_object_id   uuid        not null,
    source                      varchar(20) not null,
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now(),

    constraint uk_group_status_history_object_id unique (object_id),
    constraint fk_group_status_history_group foreign key (group_object_id) references item_group (object_id) on delete cascade,
    constraint fk_group_status_history_from  foreign key (from_group_status_object_id) references group_status (object_id),
    constraint fk_group_status_history_to    foreign key (to_group_status_object_id)   references group_status (object_id),
    constraint ck_group_status_history_source check (source in ('created', 'action', 'cascade'))
);

create index idx_group_status_history_group on group_status_history (group_object_id, created_at);
