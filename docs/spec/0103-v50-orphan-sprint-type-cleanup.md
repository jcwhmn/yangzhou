# 0103 — V50 数据清理:删存量无组的残留 sprint 类型(YPJ-152)

> 来源:V48(spec 0100 切片A)保留语义未删行的已知残留;0101 案B 后,无组但有 level-1 类型的项目侧栏仍显示 sprint 段,违背「sprint 概念完全用户配置」原则(2026-11-06 用户拍板清理)。

## ⚠ 数据变更(置顶,显要标注)

本迁移**物理删除数据行**(`item_group_type`)。删除条件与删除守卫语义(0100 拍板:`builtin || hasGroups`)完全一致——无组即可删,只把"守卫允许人手删"提前为"迁移自动清"。用户拍板见 YPJ-152 描述。

## 一、需求

- 删除所有存量项目中 `type_key='sprint'` 且**无 item_group 引用**的类型行(残留种子)。
- 有组的不动(在用);release 不动(builtin 保留,0100 拍板);新项目本就不种 sprint(0100 切片B),迁移后世界不再产生该形态。
- 幂等:迁移只跑一次,条件天然可重入。

## 二、现状实锤(2026-11-06 查库)

| 库 | 项目 | sprint 组数 | 处置 |
|---|---|---|---|
| yangzhou(prod) | YPJ | 11 | 不动 |
| yangzhou(prod) | GAO | 0 | **删** |
| yangzhou(prod) | GAOYOU | 3 | 不动 |
| yangzhou_dev(dev) | YPJ | 4 | 不动 |

dev 库 NGX 已在 0101 验收时手动删过(204)。

## 三、详细设计

```sql
-- V50__orphan_sprint_type_cleanup.sql
delete from item_group_type t
where t.type_key = 'sprint'
  and not exists (
    select 1 from item_group g
    where g.project_object_id = t.project_object_id
      and g.type = t.type_key
  );
```

- 单条 delete + not exists,无结构变更 → `entities.puml` 不动、cleanDatabase 清单不动(非新表)。
- 不加 `builtin=false` 条件:V48 已把全部存量 sprint 行置 false,新项目不种 sprint,`type_key='sprint' ∧ 无组` 在迁移后世界只剩残留(用户自建同名类型且零组的情况重建成本≈0,可接受,注释说明)。

## 四、测试

- 纯数据清理、条件一行、不可在集成测试中模拟"迁移前世界"(Flyway 起点即最新)→ **不写自动化测试**(需求矩阵:平凡迁移);验证 = 本机 rebuild prod 后查库实锤 + 单元/集成全绿回归。
- 手工测试用例(用户 build/debug):
  1. `script/app-infra.ps1 rebuild prod` → 启动日志 Flyway V50 应用 → 查库 `GAO` 无 sprint 类型行,`GAOYOU`/`YPJ` 的在
  2. GAO 侧栏无 Sprint 段(0101 案B判据)
  3. YPJ/GAOYOU sprint 功能不回归
  4. `rebuild dev` → V50 应用,0 行受影响,无异常
