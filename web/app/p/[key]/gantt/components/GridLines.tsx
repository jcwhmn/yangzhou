"use client";

import { Box } from "@mui/material";
import { type HeaderRow } from "../types";

/** 日/周界淡分割线(YPJ-239):取刻度头日/时 tick 画淡竖线,周界略深。
 * 压在条下层(与 MilestoneLines 同位,先渲染即在下)、pointerEvents none。 */
export function GridLines({ headerRows }: { headerRows: HeaderRow[] }) {
  const dayRow =
    headerRows.find((r) => r.gran === "day") ??
    headerRows.find((r) => r.gran === "hour");
  const weekRow = headerRows.find((r) => r.gran === "week");
  if (!dayRow) return null;
  const weekXs = new Set(weekRow?.ticks.map((t) => t.x) ?? []);
  return (
    <>
      {dayRow.ticks.map((t) => (
        <Box
          key={t.key}
          sx={{
            position: "absolute",
            left: t.x,
            top: 0,
            bottom: 0,
            width: "1px",
            bgcolor: weekXs.has(t.x) ? "divider" : "text.disabled",
            opacity: weekXs.has(t.x) ? 0.6 : 0.2,
            pointerEvents: "none",
          }}
        />
      ))}
    </>
  );
}
