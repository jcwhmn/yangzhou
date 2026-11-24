-- YPJ-274(spec 0167):item 关闭理由。列可空:NULL = 票未处于终态(未关或已 reopen)。
-- 是否询问理由挂在 workflow 源状态上(从此状态迁出到终态时前端须弹窗),存量 false = 行为不变。
alter table status add column require_close_reason boolean not null default false;

alter table item add column close_reason varchar(20);
alter table item add column close_note text;
alter table item
    add constraint ck_item_close_reason
    check (close_reason in ('done', 'wontfix', 'expired', 'duplicate', 'superseded'));

-- 存量:仅未删除的终态票回填 done(历史非完成关闭无法追溯,口径=全视为正常完成;软删除票不回填,
-- 回收站恢复时由服务层补 done)。非终态票留 NULL——它们没关,不是缺数据。
update item i
   set close_reason = 'done'
  from status s
 where s.object_id = i.status_object_id
   and s.status_type = 'FINAL'
   and i.deleted_at is null;

-- 活动日志新类型 close_reason_changed(终态票事后改理由;同 V16/V40/V128 惯例扩白名单)
alter table item_activity drop constraint ck_item_activity_kind;
alter table item_activity add constraint ck_item_activity_kind
    check (kind in ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed',
                    'github_branch_created', 'dates_changed', 'attribute_changed', 'estimate_changed',
                    'close_reason_changed'));
