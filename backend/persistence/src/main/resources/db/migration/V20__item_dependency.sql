-- JCW-121(实际 V20):JCW-131 V9-S3 item 依赖(阻塞关系,spec docs/spec/0007-v9-read-model-collab.md)
-- 语义:本 item 依赖 depends_on_item;依赖未到终态 → 本 item「⛔ 被阻塞」(仅标识+过滤,不强制流转)

create table item_dependency (
    id                  bigint generated always as identity primary key,
    object_id           uuid        not null,
    item_id             bigint      not null,
    depends_on_item_id  bigint      not null,
    created_at          timestamptz not null default now(),
    constraint uk_item_dependency_object_id  unique (object_id),
    constraint uk_item_dependency_pair       unique (item_id, depends_on_item_id),
    constraint ck_item_dependency_self       check (item_id <> depends_on_item_id),
    constraint fk_item_dependency_item       foreign key (item_id) references item (id) on delete cascade,
    constraint fk_item_dependency_depends_on foreign key (depends_on_item_id) references item (id) on delete cascade
);

create index idx_item_dependency_item on item_dependency (item_id);
create index idx_item_dependency_depends_on on item_dependency (depends_on_item_id);
