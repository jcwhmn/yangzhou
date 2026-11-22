"use client";

import { Stack, Typography } from "@mui/material";
import { fmtDateTime, parseLocal } from "@/lib/dates";
import { type GanttRow } from "../types";
import { estimateHoursOf } from "../utils/timeCalculations";

/** 条上 hover 详情卡(YPJ-249 自 page.tsx 原样搬出,零语义变化;YPJ-245 返工行「上次完成」在此) */
export function HoverCard({
  r,
  wd,
}: {
  r: GanttRow;
  wd: [number, number, boolean] | null;
}) {
  const estH = estimateHoursOf(
    r.estimateValue,
    r.estimateUnit,
    wd?.[0] ?? 0,
    wd?.[1] ?? 1440,
  );
  const actH =
    r.actualStart && r.actualEnd
      ? Math.max(
          0,
          (+parseLocal(r.actualEnd) - +parseLocal(r.actualStart)) / 3600000,
        )
      : null;
  const fmtH = (h: number) => `${+h.toFixed(1)}h`;
  return (
    <Stack spacing={0.25}>
      <Typography variant="caption" sx={{ fontWeight: 600 }}>
        {r.number} {r.title}
      </Typography>
      <Typography variant="caption">
        {r.statusName}
        {r.assigneeName ? ` · ${r.assigneeName}` : ""}
      </Typography>
      <Typography variant="caption">
        计划:{r.startDate ? fmtDateTime(r.startDate) : "?"} →{" "}
        {r.dueDate ? fmtDateTime(r.dueDate) : "?"}
      </Typography>
      {estH !== null && (
        <Typography variant="caption">预估工时:{fmtH(estH)}</Typography>
      )}
      {r.actualStart && (
        <Typography variant="caption">
          实际:{fmtDateTime(r.actualStart)} →{" "}
          {r.actualEnd && r.statusType === "FINAL"
            ? fmtDateTime(r.actualEnd)
            : "进行中"}
          {actH !== null ? ` · 工时 ${fmtH(actH)}` : ""}
        </Typography>
      )}
      {r.actualEnd && r.statusType !== "FINAL" && (
        <Typography variant="caption">
          上次完成:{fmtDateTime(r.actualEnd)}(返工中,SRS §6.6)
        </Typography>
      )}
      {r.anchorDate && (
        <Typography variant="caption">
          锚点:{fmtDateTime(r.anchorDate)}(依赖落位)
        </Typography>
      )}
      {r.checklistTotal ? (
        <Typography variant="caption">
          清单:{r.checklistDone ?? 0}/{r.checklistTotal}
        </Typography>
      ) : null}
    </Stack>
  );
}
