-- JCW-112: 建分支留痕(V6-S3)
-- 新 kind:github_branch_created( yangzhou 发起的分支创建;关联/流转照旧走 github_status_changed)

alter table item_activity drop constraint ck_item_activity_kind;

alter table item_activity add constraint ck_item_activity_kind
    check (kind in ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed',
                    'github_branch_created'));
