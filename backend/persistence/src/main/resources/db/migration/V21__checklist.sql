-- JCW-133: V9-S5 检查清单(spec docs/spec/0007-v9-read-model-collab.md)
-- item 内步骤清单;text + done;item 删级联清

create table checklist_item (
    id          bigint generated always as identity primary key,
    object_id   uuid         not null,
    item_id     bigint       not null,
    text        varchar(500) not null,
    done        boolean      not null default false,
    position    integer      not null default 0,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uk_checklist_item_object_id unique (object_id),
    constraint fk_checklist_item_item      foreign key (item_id) references item (id) on delete cascade
);

create index idx_checklist_item_item on checklist_item (item_id, position);
