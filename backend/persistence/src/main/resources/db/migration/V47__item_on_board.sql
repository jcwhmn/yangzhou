-- YPJ-144(spec 0099):看板拉入/退出——item.on_board 显式执行面标记。
-- 拉入 = 置标记(有 active sprint 同时挂最早 active);退回 = 清标记 + 移出非 completed sprint 成员行。
-- 谓词:backlog 排除标记 item;看板(无 active)= backlog 补集(自动吸收标记)。
alter table item add column on_board boolean not null default false;
