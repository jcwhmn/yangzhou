"use client";

import { Box } from "@mui/material";
import { type GanttRow } from "../types";
import { ProgressOverlay } from "./ProgressOverlay";

/** 计划条/里程碑菱形/计划层圆点(YPJ-249 自 page.tsx 原样搬出,零语义变化;黄覆盖段拆 ProgressOverlay) */
export function PlanBar({
  r,
  visible,
  isFinal,
  left,
  width,
  planIsBar,
  dueOnly,
  caseNo,
  fillW,
}: {
  r: GanttRow;
  visible: boolean;
  isFinal: boolean;
  left: number;
  width: number;
  planIsBar: boolean;
  dueOnly: boolean;
  caseNo: number;
  fillW: number;
}) {
  return (
    <Box
      data-final={isFinal ? "" : undefined}
      data-testid={`gantt-bar-${r.number}`}
      sx={{
        position: "absolute",
        left,
        width,
        top: 5,
        display: visible ? "block" : "none",
      }}
    >
      {planIsBar ? (
        <Box
          sx={{
            position: "relative",
            height: 12,
            borderRadius: 1,
            bgcolor: r.overdue ? "error.main" : "primary.main",
            opacity: 0.85,
          }}
        >
          {fillW > 0 && (
            <ProgressOverlay fillW={fillW} width={width} number={r.number} />
          )}
        </Box>
      ) : dueOnly ? (
        <Box
          sx={{
            width: 12,
            height: 12,
            transform: "rotate(45deg)",
            bgcolor: r.overdue ? "error.main" : "warning.main",
            opacity: 1,
            mx: "auto",
          }}
        />
      ) : (
        // spec 0151 五:case1/3 计划层圆点——位置=group 开始(case1 未开始)/ start 或依赖锚(case3)
        <Box
          data-testid={`gantt-${caseNo === 1 ? "grouppoint" : r.anchorDate && r.startDate === null ? "anchor" : "plandot"}-${r.number}`}
          sx={{
            width: 3,
            height: 12,
            borderRadius: 1,
            bgcolor: "primary.main",
          }}
        />
      )}
    </Box>
  );
}
