-- YPJ-186(spec 0128):item 级工作量预估(快照语义:选档=写该档数值+单位,项目改档不翻动历史)。
-- unit 是封闭枚举(180 拍板方案 B),按仓纪律落 CHECK;value 范围校验在 service(与刻度档一致)。
ALTER TABLE item ADD COLUMN estimate_value DOUBLE PRECISION NULL;
ALTER TABLE item ADD COLUMN estimate_unit VARCHAR(8) NULL CHECK (estimate_unit IN ('hour', 'day', 'week'));

-- 活动日志新类型 estimate_changed(同 V16/V40 惯例:每次新增 kind 扩白名单)
ALTER TABLE item_activity DROP CONSTRAINT ck_item_activity_kind;
ALTER TABLE item_activity ADD CONSTRAINT ck_item_activity_kind
    CHECK (kind IN ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed',
                    'github_branch_created', 'dates_changed', 'attribute_changed', 'estimate_changed'));
