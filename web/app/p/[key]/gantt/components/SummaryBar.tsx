"use client";

import { Box, Chip } from "@mui/material";

/** 父行汇总条 + 折叠聚合徽标(YPJ-249 自 page.tsx 原样搬出,零语义变化;collapsed.has 判定留调用点) */
export function SummaryBar({
  summary,
  summaryLeft,
  summaryWidth,
  collapsed,
  descendants,
  number,
}: {
  summary: [string, string] | null;
  summaryLeft: number;
  summaryWidth: number;
  collapsed: boolean;
  descendants: number;
  number: string;
}) {
  return (
    <>
      <Box
        data-summary={summary ? "" : undefined}
        sx={{
          position: "absolute",
          left: summaryLeft,
          width: summaryWidth,
          top: 4,
          display: summary ? "block" : "none",
        }}
      >
        <Box
          sx={{
            height: 6,
            borderRadius: 1,
            bgcolor: "#94A3B8", // spec 0165 §3.4:中性灰蓝降权(原 warning.main 橙不抢权重)
            opacity: 0.75,
          }}
        />
      </Box>
      {/* 折叠聚合徽标(YPJ-222) */}
      {summary && collapsed && (
        <Chip
          label={`+${descendants} 子任务`}
          size="small"
          data-testid={`gantt-collapsed-${number}`}
          sx={{
            position: "absolute",
            left: summaryLeft + summaryWidth + 4,
            top: 0,
            height: 16,
            fontSize: 10,
          }}
        />
      )}
    </>
  );
}
