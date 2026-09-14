-- JCW-120: V8-S1 item 日期(排期地基,spec docs/spec/0006-v8-time-dimension.md)
-- startDate/dueDate 均可空(只有截止期也允许);活动日志新增 dates_changed

alter table item add column start_date date;
alter table item add column due_date date;

alter table item add constraint ck_item_dates
    check (start_date is null or due_date is null or start_date <= due_date);

alter table item_activity drop constraint ck_item_activity_kind;

alter table item_activity add constraint ck_item_activity_kind
    check (kind in ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed',
                    'github_branch_created', 'dates_changed'));
