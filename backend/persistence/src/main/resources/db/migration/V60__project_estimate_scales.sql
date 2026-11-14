-- YPJ-180(spec 0126):工作量预估·项目级刻度配置
-- estimate_scales = JSON 数组 [{key,label,value,unit}],null=内置默认;required 开关供 item 级票(YPJ-186)消费。
ALTER TABLE projects ADD COLUMN estimate_scales TEXT NULL;
ALTER TABLE projects ADD COLUMN estimate_required BOOLEAN NOT NULL DEFAULT FALSE;
