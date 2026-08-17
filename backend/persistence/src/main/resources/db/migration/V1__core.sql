-- yangzhou V1 核心骨架(ADR 0001-0003):
-- workspace(隐式租户) > member + attribute_definition(词表) ; project(key+workflow) > status > item(同质树) > requirement
-- 约定(chess 平移): identity 主键 + object_id 公共 ID + 命名约束 + CHECK 枚举

create table workspace (
    id          bigint generated always as identity primary key,
    object_id   uuid           not null,
    name        varchar(100)   not null default 'default',
    created_at  timestamptz    not null default now(),
    updated_at  timestamptz    not null default now(),
    constraint uk_workspace_object_id unique (object_id)
);

create table member (
    id            bigint generated always as identity primary key,
    object_id     uuid          not null,
    workspace_id  bigint        not null,
    username      varchar(50)   not null,
    password_hash varchar(200)  not null,
    display_name  varchar(100)  not null,
    created_at    timestamptz   not null default now(),
    updated_at    timestamptz   not null default now(),
    constraint uk_member_object_id unique (object_id),
    constraint uk_member_username  unique (username),
    constraint fk_member_workspace foreign key (workspace_id) references workspace (id)
);

create table attribute_definition (
    id           bigint generated always as identity primary key,
    object_id    uuid         not null,
    workspace_id bigint       not null,
    name         varchar(50)  not null,
    kind         varchar(20)  not null default 'skill',
    leveled      boolean      not null default false,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    constraint uk_attribute_definition_object_id      unique (object_id),
    constraint uk_attribute_definition_workspace_name unique (workspace_id, name),
    constraint fk_attribute_definition_workspace      foreign key (workspace_id) references workspace (id),
    constraint ck_attribute_definition_kind            check (kind in ('skill', 'label'))
);

create table capability (
    id                      bigint generated always as identity primary key,
    object_id               uuid         not null,
    member_id               bigint       not null,
    attribute_definition_id bigint       not null,
    level                   integer,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now(),
    constraint uk_capability_object_id        unique (object_id),
    constraint uk_capability_member_attribute  unique (member_id, attribute_definition_id),
    constraint fk_capability_member            foreign key (member_id) references member (id),
    constraint fk_capability_attribute         foreign key (attribute_definition_id) references attribute_definition (id),
    constraint ck_capability_level              check (level is null or level between 1 and 4)
);

create table project (
    id               bigint generated always as identity primary key,
    object_id        uuid         not null,
    workspace_id     bigint       not null,
    key              varchar(10)  not null,
    name             varchar(100) not null,
    last_item_number integer      not null default 0,
    archived_at      timestamptz,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    constraint uk_project_object_id   unique (object_id),
    constraint uk_project_workspace_key unique (workspace_id, key),
    constraint fk_project_workspace   foreign key (workspace_id) references workspace (id)
);

create table status (
    id         bigint generated always as identity primary key,
    object_id  uuid         not null,
    project_id bigint       not null,
    name       varchar(50)  not null,
    is_final   boolean      not null default false,
    position   integer      not null default 0,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint uk_status_object_id   unique (object_id),
    constraint uk_status_project_name unique (project_id, name),
    constraint fk_status_project     foreign key (project_id) references project (id)
);

create table item (
    id              bigint generated always as identity primary key,
    object_id       uuid          not null,
    project_id      bigint        not null,
    number          integer       not null,
    title           varchar(200)  not null,
    description     text,
    type            varchar(20)   not null default 'task',
    parent_object_id uuid,
    status_object_id uuid         not null,
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    constraint uk_item_object_id      unique (object_id),
    constraint uk_item_project_number unique (project_id, number),
    constraint fk_item_project        foreign key (project_id) references project (id),
    constraint fk_item_parent         foreign key (parent_object_id) references item (object_id),
    constraint fk_item_status         foreign key (status_object_id) references status (object_id),
    constraint ck_item_type            check (type in ('task', 'bug', 'goal', 'story'))
);

create table requirement (
    id                      bigint generated always as identity primary key,
    object_id               uuid        not null,
    item_id                 bigint      not null,
    attribute_definition_id bigint      not null,
    min_level               integer,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    constraint uk_requirement_object_id       unique (object_id),
    constraint uk_requirement_item_attribute   unique (item_id, attribute_definition_id),
    constraint fk_requirement_item             foreign key (item_id) references item (id),
    constraint fk_requirement_attribute        foreign key (attribute_definition_id) references attribute_definition (id),
    constraint ck_requirement_min_level         check (min_level is null or min_level between 1 and 4)
);

create index idx_item_parent      on item (parent_object_id);
create index idx_requirement_item on requirement (item_id);
create index idx_capability_member on capability (member_id);
