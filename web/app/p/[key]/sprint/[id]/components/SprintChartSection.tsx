"use client";

import { useEffect, useState } from "react";
import { LineChart } from "@mui/x-charts/LineChart";
import {
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { ChartBy } from "./ChartBy";
import { type Item } from "../types";

/** SprintChartSection(YPJ-252 spec 0155 拍板 3:chartView/bd+burndown fetch 内置,组件常挂载 mount 即拉时序不变) */
export function SprintChartSection({
  projectKey,

  groupId,

  items,

  statusOrder,
}: {
  projectKey: string;

  groupId: string;

  items: Item[] | null;

  statusOrder: string[];
}) {
  // YPJ-73:图表视图切换(燃専数据不足时显示空态而非整块消失)
  const [chartView, setChartView] = useState<
    "burndown" | "byStatus" | "byAssignee"
  >("burndown");
  // YPJ-46:burndown(剩余 vs 理想线);端点要求 sprint 有起止日期
  const [bd, setBd] = useState<{
    ideal: { date: string; remaining: number }[];
    actual: { date: string; remaining: number }[];
  } | null>(null);

  useEffect(() => {
    let alive = true;
    api<{
      ideal: { date: string; remaining: number }[];
      actual: { date: string; remaining: number }[];
    }>(`/api/projects/${projectKey}/metrics/burndown?groupId=${groupId}`)
      .then((r) => alive && setBd({ ideal: r.ideal, actual: r.actual }))
      .catch(() => alive && setBd(null));
    return () => {
      alive = false;
    };
  }, [projectKey, groupId]);

  return (
    <>
      <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1 }}>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={chartView}
          onChange={(_, v) => v && setChartView(v)}
        >
          <ToggleButton value="burndown">
            {t.sprintCharts.burndown}
          </ToggleButton>
          <ToggleButton value="byStatus">
            {t.sprintCharts.byStatus}
          </ToggleButton>
          <ToggleButton value="byAssignee">
            {t.sprintCharts.byAssignee}
          </ToggleButton>
        </ToggleButtonGroup>
      </Stack>
      {chartView === "burndown" &&
        (bd && bd.actual.length > 1 ? (
          <LineChart
            height={260}
            xAxis={[
              {
                scaleType: "time",
                data: bd.actual.map((p) => new Date(p.date)),
              },
            ]}
            series={[
              {
                label: t.flow.remaining,
                data: bd.actual.map((p) => p.remaining),
                showMark: false,
              },
              {
                label: t.flow.ideal,
                data: bd.ideal.map((p) => p.remaining),
                showMark: false,
              },
            ]}
            sx={{ mb: 2 }}
          />
        ) : (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: "block", mb: 2 }}
          >
            {t.sprintCharts.burndownEmpty}
          </Typography>
        ))}
      {chartView !== "burndown" && items !== null && items.length > 0 && (
        <ChartBy
          view={chartView}
          items={items}
          statusOrder={statusOrder}
          sx={{ mb: 2 }}
        />
      )}
    </>
  );
}
