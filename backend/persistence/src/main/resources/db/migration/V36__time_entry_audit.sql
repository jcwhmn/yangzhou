-- spec 0060 / YPJ-61:审计基线收尾——time_entry 补全四元组(业务表必带;V34 形态:bigint nullable 无 FK,不回填存量)。
-- 其余 6 张缺列表为 append-only 事件/历史表或纯交叉表,行为者已在业务列(actor_member_id/member_id/added_at),按需跳过,裁决记档 spec 0060。
alter table time_entry add column updated_at timestamptz, add column created_by bigint, add column updated_by bigint;
