-- YPJ-238(spec 0166):看板视图级 item 排序记忆(项目 × item);方案 B,与组归属脱钩
create table board_item_order (
    id                bigint      generated always as identity primary key,
    object_id         uuid        not null,
    project_object_id uuid        not null,
    item_object_id    uuid        not null,
    position          int         not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint uk_board_item_order_object_id unique (object_id),
    constraint uk_board_item_order_project_item unique (project_object_id, item_object_id),
    constraint fk_board_item_order_project foreign key (project_object_id) references project (object_id) on delete cascade,
    constraint fk_board_item_order_item    foreign key (item_object_id) references item (object_id) on delete cascade,
    constraint ck_board_item_order_position check (position >= 0)
);

create index idx_board_item_order_project on board_item_order (project_object_id, position);
