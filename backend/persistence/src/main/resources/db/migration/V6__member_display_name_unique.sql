-- JCW-87 后续:成员显示名 workspace 内唯一
-- (同名成员会让 CLI 按名寻址产生歧义;V4 已应用的环境由本迁移补约束)

delete from member a
using member b
where a.workspace_id = b.workspace_id
  and a.display_name = b.display_name
  and a.object_id > b.object_id
  and a.password_hash is null;  -- 保留最早建的那条;若两条都有凭据则人工处理

create unique index ux_member_workspace_display_name on member (workspace_id, display_name);
