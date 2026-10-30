-- YPJ-120(spec 0089):status 增加进行态属性(始/终/进行三属性齐)。
-- 回填只认预置三态名;自定义状态由用户后续经 Workflow API 设置。
ALTER TABLE status ADD COLUMN is_in_progress BOOLEAN NOT NULL DEFAULT false;
UPDATE status SET is_in_progress = true WHERE name = 'In Progress';
