-- JCW-130: V9-S2 收藏(member 维度,跨浏览器/重装持久;V9-Q12)
-- 项目删除级联清收藏

create table favorite (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    member_id  bigint      not null,
    project_id bigint      not null,
    created_at timestamptz not null default now(),
    constraint uk_favorite_object_id     unique (object_id),
    constraint uk_favorite_member_project unique (member_id, project_id),
    constraint fk_favorite_member        foreign key (member_id) references member (id) on delete cascade,
    constraint fk_favorite_project       foreign key (project_id) references project (id) on delete cascade
);

create index idx_favorite_member on favorite (member_id, created_at);
