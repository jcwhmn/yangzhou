-- JCW-111: GitHub 驱动的流转留痕(V6-S2)
-- 新 kind:github_status_changed;actor 可空 = 系统事件(GitHub),非登录成员操作

alter table item_activity drop constraint ck_item_activity_kind;

alter table item_activity add constraint ck_item_activity_kind
    check (kind in ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed'));

alter table item_activity alter column actor_member_id drop not null;
