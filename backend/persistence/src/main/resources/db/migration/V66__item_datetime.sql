-- YPJ-208(spec 0138):item 排期 DATE→timestamp(墙钟,分钟级);project 工作日历两列(切片 b 启用)
-- ck_item_dates 约束随 ALTER TYPE 保留,timestamp 比较语义不变

alter table item alter start_date type timestamp using start_date::timestamp;
alter table item alter due_date type timestamp using due_date::timestamp;

alter table project add column workday_start varchar(5) not null default '09:00';
alter table project add column workday_end varchar(5) not null default '18:00';
