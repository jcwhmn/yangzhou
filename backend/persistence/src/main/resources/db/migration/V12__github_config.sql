-- JCW-110: GitHub 集成配置面(V6-S1,spec docs/spec/0004-v6-github-integration.md)
-- workspace 级 PAT(bot 身份)+ member.github_username(作者映射)+ 项目仓库挂载(1–N)
-- + item 外部 git 引用(分支/PR,uk 即轮询幂等锚点)+ 每项目「事件→状态」映射 4 槽

alter table workspace add column github_token varchar(200);

alter table member add column github_username varchar(100);

create unique index uk_member_workspace_github_username
    on member (workspace_id, github_username)
    where github_username is not null;

create table project_repo (
    id          bigint generated always as identity primary key,
    object_id   uuid         not null,
    project_id  bigint       not null,
    repo        varchar(200) not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uk_project_repo_object_id      unique (object_id),
    constraint uk_project_repo_project_repo   unique (project_id, repo),
    constraint fk_project_repo_project        foreign key (project_id) references project (id) on delete cascade,
    constraint ck_project_repo_format         check (repo ~ '^[^[:space:]]+/[^[:space:]]+$')
);

create index idx_project_repo_project on project_repo (project_id);

create table item_git_ref (
    id          bigint generated always as identity primary key,
    object_id   uuid         not null,
    item_id     bigint       not null,
    kind        varchar(10)  not null,
    repo        varchar(200) not null,
    ref         varchar(200) not null,
    url         varchar(500),
    state       varchar(10),
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uk_item_git_ref_object_id      unique (object_id),
    constraint uk_item_git_ref_repo_kind_ref  unique (repo, kind, ref),
    constraint fk_item_git_ref_item           foreign key (item_id) references item (id) on delete cascade,
    constraint ck_item_git_ref_kind           check (kind in ('branch', 'pr')),
    constraint ck_item_git_ref_state          check (state in ('open', 'merged', 'closed'))
);

create index idx_item_git_ref_item on item_git_ref (item_id);

create table project_workflow_rule (
    id               bigint generated always as identity primary key,
    object_id        uuid         not null,
    project_id       bigint       not null,
    event_type       varchar(30)  not null,
    status_object_id uuid         not null,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    constraint uk_project_workflow_rule_object_id  unique (object_id),
    constraint uk_project_workflow_rule_project_event unique (project_id, event_type),
    constraint fk_project_workflow_rule_project    foreign key (project_id) references project (id) on delete cascade,
    constraint fk_project_workflow_rule_status     foreign key (status_object_id) references status (object_id) on delete cascade,
    constraint ck_project_workflow_rule_event      check (event_type in ('branch_created', 'pr_opened', 'pr_merged', 'pr_closed_unmerged'))
);

create index idx_project_workflow_rule_project on project_workflow_rule (project_id);
