-- JCW-87: 虚拟成员 + Team 分组(V2 spec 0002)
-- password_hash/username 可空:空 = 虚拟成员(只参与匹配,无登录)
-- team = workspace 级纯分组(池);team_member 多对多;匹配不看 Team(Q6)

alter table member alter column password_hash drop not null;
alter table member alter column username drop not null;

create table team (
    id           bigint generated always as identity primary key,
    object_id    uuid         not null,
    workspace_id bigint       not null,
    name         varchar(50)  not null,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    constraint uk_team_object_id      unique (object_id),
    constraint uk_team_workspace_name unique (workspace_id, name),
    constraint fk_team_workspace      foreign key (workspace_id) references workspace (id)
);

create table team_member (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    team_id    bigint      not null,
    member_id  bigint      not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_team_member_object_id   unique (object_id),
    constraint uk_team_member_team_member  unique (team_id, member_id),
    constraint fk_team_member_team         foreign key (team_id) references team (id) on delete cascade,
    constraint fk_team_member_member       foreign key (member_id) references member (id) on delete cascade
);

create index idx_team_member_member on team_member (member_id);
