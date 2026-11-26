# spec 0175 — YPJ-284 组生命周期时间线(历史段)

> 来源:YPJ-269 grill Q1b 拍 B 案(269 只做侧栏+史表落库,展示剥离至此票);2026-11-26 实现中期用户扩围拍板:范围 A(组迁移+成员进出)、排序 DESC(后进先见)。本 spec 于实现中途补立(流程偷跑复盘:功能票应 spec 先行,见 handoff)。

## 目的与范围

group 详情页(sprint/[id])展示组生命周期时间线,含两类事件:

1. **组状态迁移**(V72 `group_status_history`):created/action/cascade;
2. **成员进出**(`item_group_member_history`,YPJ-54 起):join=added_at,leave=removed_at(一行任期拆两事件行)。

明确不在范围(否决/剥离):

- **B 案剥离**:成员 item 的开始/完成里程碑(item_status_history)——大组成员数百行,简版形态装不下,需折叠/分页另票;
- item_activity 属性变更日志(item 详情页 ActivityTab 已有,组级不重复);
- 分页/虚拟滚动(当前规模最大 44 行,不需要);
- 存量组回填(对齐 V31/V72「算不出即不造」先例;存量组经成员史自然有数据,2026-10-12 起全量覆盖)。

## 端点契约

`GET /api/projects/{key}/groups/{groupId}/timeline`(取代实现中期曾用名 `/status-history`,未合并无契约包袱)

```kotlin
data class GroupTimelineDto(
    val objectId: UUID,
    val kind: String,          // "status" | "member"
    val createdAt: String,     // ISO;统一时间列
    val from: String? = null,  // status 行;null=初始进入
    val to: String? = null,    // status 行;破损态名兜底 "-" 不 500
    val source: String? = null,// status 行;created/action/cascade
    val itemTitle: String? = null, // member 行;item 已删兜底 "-"
    val action: String? = null,    // member 行;join/leave
)
```

- 排序:统一按 createdAt **DESC**(后进先见,用户拍板);同刻 tie-break 依赖稳定排序(状态行先于成员行入列)。
- 404:组不存在(对齐组详情)。

## 展示口径

- 区块:详情页下方全宽,标题「时间线」,镜像 item ActivityTab 形态(caption 行,~40 行组件);
- 行文案:`{时间} · {source中文:创建/操作/联动} · {from ?? "—"} → {to}`(status);`{时间} · 成员 · + {itemTitle}` / `− {itemTitle}`(member);
- 触发重拉:组件自取数,依赖页面 `sprintVer`(数据变更信号;成员进出路径补 bump);
- 空态:无任何行(纯存量组)显示「暂无记录」,不报错;取数失败同空态兜底。

## 数据源覆盖(2026-11-26 prod 实锤)

| 史表 | 起点 | 覆盖 | 体量 |
|---|---|---|---|
| group_status_history | 今日 | 仅新建组 | 小 |
| item_group_member_history | 2026-10-12 | 全部 24 组 355 行 | 大组 ≤44 行 |
| item_status_history(B 案,不入) | 2026-10-05 | 361/366 item | 大组数百行 |

## 验收清单

- [ ] 时间线 DESC:后发生的行在上;覆盖 start/complete/reopen 全动作
- [ ] 成员进出行:加入/移出各一行,带 item 名;存量组(仅成员史)即有数据
- [ ] 空态文案,页面无异常;未知组 404
- [ ] 改动限 itemgroup slice + sprint 详情页,不动 item 侧
