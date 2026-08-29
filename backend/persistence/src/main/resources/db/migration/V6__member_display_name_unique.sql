-- JCW-87 后续:成员显示名 workspace 内唯一
-- (同名成员会让 CLI 按名寻址产生歧义)
-- 注意:若环境中已存在同名成员,本迁移会因唯一索引失败——请人工合并/清理后重跑
--      (不做自动删除:成员可能已挂能力与指派,越权清理有风险)

create unique index ux_member_workspace_display_name on member (workspace_id, display_name);
