-- JCW-80: Workflow 数据化(ADR/kaneo 模式:workflow 是 project 上的 data)
-- status.icon:看板列图标
-- status_transition:合法迁移表;**无行 = 自由迁移**(DOMAIN.md 领域规则 3);删 status 级联清迁移

alter table status add column icon varchar(50);

create table status_transition (
    id             bigint generated always as identity primary key,
    object_id      uuid        not null,
    project_id     bigint      not null,
    from_status_id bigint      not null,
    to_status_id   bigint      not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint uk_status_transition_object_id unique (object_id),
    constraint uk_status_transition_pair       unique (from_status_id, to_status_id),
    constraint fk_status_transition_project    foreign key (project_id) references project (id),
    constraint fk_status_transition_from       foreign key (from_status_id) references status (id) on delete cascade,
    constraint fk_status_transition_to         foreign key (to_status_id) references status (id) on delete cascade
);

create index idx_status_transition_project on status_transition (project_id);
