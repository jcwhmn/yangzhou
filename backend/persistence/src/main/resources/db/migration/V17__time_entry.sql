-- JCW-122: V8-S3 工时(TimeEntry,spec docs/spec/0006-v8-time-dimension.md)
-- 计时器:ended_at 为空 = 计时中,全局唯一(开新自动停旧,服务层保证)
-- 补录:minutes 直填;与起止互斥由服务层校验;进行中条目不计 minutes(读时折算)

create table time_entry (
    id          bigint generated always as identity primary key,
    object_id   uuid        not null,
    item_id     bigint      not null,
    member_id   bigint      not null,
    started_at  timestamptz not null,
    ended_at    timestamptz,
    minutes     integer,
    note        text,
    created_at  timestamptz not null default now(),
    constraint uk_time_entry_object_id unique (object_id),
    constraint fk_time_entry_item      foreign key (item_id) references item (id) on delete cascade,
    constraint fk_time_entry_member    foreign key (member_id) references member (id),
    constraint ck_time_entry_minutes   check (minutes is null or minutes > 0),
    constraint ck_time_entry_span      check (ended_at is null or ended_at > started_at)
);

create index idx_time_entry_member on time_entry (member_id);
create index idx_time_entry_item on time_entry (item_id, started_at);
