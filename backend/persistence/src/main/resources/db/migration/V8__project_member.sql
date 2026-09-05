create table project_member (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    project_id bigint      not null,
    member_id  bigint      not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_project_member_object_id unique (object_id),
    constraint uk_project_member_pair       unique (project_id, member_id),
    constraint fk_pm_project foreign key (project_id) references project (id) on delete cascade,
    constraint fk_pm_member  foreign key (member_id)  references member (id)  on delete cascade
);

create index idx_project_member_project on project_member (project_id);
create index idx_project_member_member on project_member (member_id);