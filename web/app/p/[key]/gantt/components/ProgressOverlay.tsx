"use client";

import { Box } from "@mui/material";

/** 黄覆盖段 = 进度(YPJ-255 spec 0159 拍板:覆盖=完成度,画在计划条上;原深色清单遮罩与覆盖合一移除) */
export function ProgressOverlay({
  fillW,
  width,
  number,
}: {
  fillW: number;
  width: number;
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
    ></Box>
  );
}
