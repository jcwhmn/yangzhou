-- JCW-115: 通知(V7-S1,spec docs/spec/0005-v7-notifications.md)
-- item.created_by:创建者(可空,存量 null;通知收件人降级 assignee+评论者)
-- notification:站内通知(收件人维度;item 删级联清)

alter table item add column created_by bigint;

alter table item
    add constraint fk_item_created_by foreign key (created_by) references member (id);

create table notification (
    id                  bigint generated always as identity primary key,
    object_id           uuid        not null,
    recipient_member_id bigint      not null,
    item_id             bigint      not null,
    kind                varchar(20) not null,
    actor_member_id     bigint,
    old_value           text,
    new_value           text,
    read_at             timestamptz,
    created_at          timestamptz not null default now(),
    constraint uk_notification_object_id unique (object_id),
    constraint fk_notification_recipient foreign key (recipient_member_id) references member (id) on delete cascade,
    constraint fk_notification_item      foreign key (item_id) references item (id) on delete cascade,
    constraint fk_notification_actor     foreign key (actor_member_id) references member (id),
    constraint ck_notification_kind      check (kind in ('status_changed', 'assigned', 'commented'))
);

create index idx_notification_recipient on notification (recipient_member_id, read_at);
