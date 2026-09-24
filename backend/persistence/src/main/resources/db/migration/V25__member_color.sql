-- JCW-141: V10-S3 颜色语义(spec docs/spec/0008-v10-depth.md)
-- member.color 调色板轮转自动分配;可编辑

alter table member add column color varchar(7);
