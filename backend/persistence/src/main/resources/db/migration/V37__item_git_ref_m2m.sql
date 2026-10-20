-- YPJ-39:branch×item 多对多——分支/PR 可挂多票
-- 旧 uk(repo,kind,ref) 全局唯一 = 1:1 锚点;放开为每票唯一后,一分支可由多票各持一行,
-- 轮询幂等判断改走 (item_id, repo, kind, ref);既有行天然满足新 uk,零数据改写。

alter table item_git_ref drop constraint uk_item_git_ref_repo_kind_ref;

alter table item_git_ref add constraint uk_item_git_ref_item_repo_kind_ref
    unique (item_id, repo, kind, ref);
