"use client";

import { Box } from "@mui/material";

/** 实际条 + 完成点(YPJ-249 自 page.tsx 原样搬出,零语义变化):下条黄/绿,深色段=清单完成率 */
export function ActualBar({
  visible,
  left,
  width,
  color,
  actPct,
  isFinal,
  completeDot,
  completeLeft,
  number,
}: {
  visible: boolean;
  left: number;
  width: number;
  color: string;
  actPct: number | null;
  isFinal: boolean;
  completeDot: boolean;
  completeLeft: number;
  number: string;
}) {
  return (
    <>
      {/* 实际条(下条):仅 无计划条的进行中(case1/3b 黄条)/ 完成(绿条)/ 返工 渲染;
                        有计划条的进行中行 = 单条覆盖模型,不再画第二条(SRS v4 §6.2,2026-11-21 用户拍板) */}
      {visible && (
        <Box
          data-testid={`gantt-actual-${number}`}
          sx={{
            position: "absolute",
            left,
            width,
            top: 24,
          }}
        >
          <Box
            sx={{
              position: "relative",
              height: 12,
              borderRadius: 1,
              bgcolor: color,
              opacity: 0.85,
            }}
          >
            {actPct !== null && (
              <Box
                sx={{
                  position: "absolute",
                  left: 0,
                  top: 0,
                  height: "100%",
                  width: `${actPct}%`,
                  borderRadius: 1,
                  bgcolor: isFinal ? "success.dark" : "warning.dark",
                }}
              />
            )}
          </Box>
        </Box>
      )}
      {/* YPJ-244(spec 0151)H5:完成点(actualComplete 有而 actualStart 无) */}
      {completeDot && (
        <Box
          data-testid={`gantt-complete-${number}`}
          sx={{
            position: "absolute",
            left: completeLeft,
            top: 26,
            width: 10,
            height: 10,
            borderRadius: "50%",
            bgcolor: isFinal ? "text.disabled" : "success.main",
            opacity: isFinal ? 0.5 : 0.85,
          }}
        />
      )}
    </>
  );
}
