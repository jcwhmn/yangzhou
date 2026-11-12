-- YPJ-173(spec 0117):onBoard 语义收窄至无组项目——有 level-1 组类型的项目标记清零。
-- 存量幽灵票(如 YPJ-109/156:onBoard=true 无 sprint,backlog 谓词排除标记 → 三不管)落回 backlog。
-- S0→有组转换的运行时触发见 ItemGroupService.createType(首个用户类型 = 转换点,同事务清标记)。
UPDATE item SET on_board = false
WHERE project_object_id IN (SELECT project_object_id FROM item_group_type WHERE level = 1);
