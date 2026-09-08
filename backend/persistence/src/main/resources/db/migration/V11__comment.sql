-- JCW-104 W2: 评论(item 讨论串,区别于自动留痕)

create table comment (
    id               bigint generated always as identity primary key,
    object_id        uuid        not null,
    item_id          bigint      not null,
    author_member_id bigint      not null,
    body             text        not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint uk_comment_object_id   unique (object_id),
    constraint fk_comment_item        foreign key (item_id) references item (id) on delete cascade,
    constraint fk_comment_author      foreign key (author_member_id) references member (id)
);

create index idx_comment_item on comment (item_id, created_at desc);
