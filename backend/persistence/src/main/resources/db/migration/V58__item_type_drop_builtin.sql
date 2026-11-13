-- YPJ-177(spec 0123 修订,2026-11-14 用户定):builtin 概念移除——种子只是初始化占位,所有 type 可增删改
-- V57 已在 dev 应用,不可改;builtin 列随本迁移摘除,守卫与 UI 特权同步退役(在用守卫保留:数据保护非类型特权)
alter table item_type drop column builtin;
