-- YPJ-216(spec 0164):组拉入缺省设置——甘特缺省画图输入;null=未设置(渲染回退:预估取刻度中档,偏移取 0)
ALTER TABLE project ADD COLUMN default_estimate_value VARCHAR(20);
ALTER TABLE project ADD COLUMN default_estimate_unit VARCHAR(10);
ALTER TABLE project ADD COLUMN default_start_offset_minutes BIGINT;
