-- spec 0177(S1/YPJ-288)§3.1:账号基座三件——member.status、member_blacklist、member_role

-- 成员状态(ACTIVE 缺省;DISABLED 禁登,过滤链即拒)。存 enum name 大写(SD JDBC name 映射,随 status_type='START' 先例;spec §3.1 原文小写,偏离已记 S1 子文档)
alter table member add column status varchar(16) not null default 'ACTIVE';
alter table member add constraint ck_member_status check (status in ('ACTIVE','DISABLED'));

-- member 级黑名单:禁用/重置密码/自助改密入列,自动过期;复职删行(spec §4.3)
create table member_blacklist (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    member_id  bigint      not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint uk_member_blacklist_object_id unique (object_id),
    constraint uk_member_blacklist_member    unique (member_id),
    constraint fk_mb_member foreign key (member_id) references member (id) on delete cascade
);

-- 统一角色叉表(S1 建表+backfill workspace admin 行;S2 启用 project 级)
create table member_role (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    member_id  bigint      not null,
    role       varchar(16) not null,
    project_id bigint,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_member_role_object_id unique (object_id),
    constraint uk_member_role_assignment unique (member_id, role, project_id),
    constraint fk_mr_member foreign key (member_id) references member (id) on delete cascade,
    constraint fk_mr_project foreign key (project_id) references project (id) on delete cascade,
    constraint ck_member_role_scope check (
        (role = 'admin' and project_id is null)
        or (role in ('editor', 'viewer') and project_id is not null)
    )
);

-- backfill:既有登录账号(bootstrap 账号,password_hash 非空)→ workspace admin(spec §3.2,每环境 1 行)
insert into member_role (object_id, member_id, role, project_id)
select gen_random_uuid(), id, 'admin', null from member where password_hash is not null;
