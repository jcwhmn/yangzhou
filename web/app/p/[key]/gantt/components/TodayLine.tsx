"use client";

import { Box } from "@mui/material";

/** 今日线(YPJ-249 自 page.tsx 原样搬出,零语义变化):轴内 info 色 2px 竖线 */
export function TodayLine({
  todayX,
  timelineW,
}: {
  todayX: number;
  timelineW: number;
}) {
  return (
    <>
      {todayX >= 0 && todayX <= timelineW && (
        <Box
          sx={{
            position: "absolute",
            left: todayX,
            top: 0,
            bottom: 0,
            width: "2px",
            bgcolor: "info.main",
            opacity: 0.5,
          }}
        />
      )}
    </>
  );
}
