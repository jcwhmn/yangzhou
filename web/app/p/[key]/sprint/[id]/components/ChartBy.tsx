"use client";

import { useMemo } from "react";
import { BarChart } from "@mui/x-charts/BarChart";
import { t } from "@/lib/texts";
import { type Item } from "../types";

// YPJ-73:sprint 页图表——按状态/按负责人的 item 计数(横向条形,数据全来自页内 items)
export function ChartBy({
  view,
  items,
  statusOrder,
  ...rest
}: {
  view: "byStatus" | "byAssignee";
  items: Item[];
  statusOrder: string[];
} & { sx?: object }) {
  const rows = useMemo(() => {
    const counts = new Map<string, number>();
    for (const i of items) {
      const k =
        view === "byStatus"
          ? i.status
          : (i.assignee ?? t.sprintCharts.unassigned);
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const order =
      view === "byStatus"
        ? [...statusOrder, ...counts.keys()].filter(
            (k, idx, a) => counts.has(k) && a.indexOf(k) === idx,
          )
        : [...counts.keys()].sort();
    return order.map((name) => ({ name, count: counts.get(name)! }));
  }, [view, items, statusOrder]);
  return (
    <BarChart
      height={260}
      layout="horizontal"
      yAxis={[{ scaleType: "band", data: rows.map((r) => r.name) }]}
      series={[{ label: t.sprintCharts.count, data: rows.map((r) => r.count) }]}
      {...rest}
    />
  );
}
