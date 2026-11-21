"use client";

import { Box } from "@mui/material";

/** 黄覆盖段 + 清单完成率遮罩(YPJ-249 自 page.tsx 原样搬出,零语义变化;仅 PlanBar 内消费) */
export function ProgressOverlay({
  fillW,
  width,
  actPct,
  number,
}: {
  fillW: number;
  width: number;
  actPct: number | null;
  number: string;
}) {
  return (
    <Box
      data-testid={`gantt-fill-${number}`}
      sx={{
        position: "absolute",
        left: 0,
        top: 0,
        height: "100%",
        width: fillW,
        borderRadius: fillW >= width ? 1 : "4px 0 0 4px",
        bgcolor: "warning.main",
        opacity: 0.9,
      }}
    >
      {/* 清单完成率遮罩:画在黄覆盖段上(基准=覆盖段宽,SRS §6.5) */}
      {actPct !== null && (
        <Box
          sx={{
            position: "absolute",
            left: 0,
            top: 0,
            height: "100%",
            width: `${actPct}%`,
            bgcolor: "warning.dark",
          }}
        />
      )}
    </Box>
  );
}
