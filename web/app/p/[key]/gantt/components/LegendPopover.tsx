"use client";

import { useState } from "react";
import {
  Box,
  IconButton,
  Popover,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import PaletteOutlined from "@mui/icons-material/PaletteOutlined";
import { TINT } from "../statusTint";

/** 图例浮层(spec 0165 SRS §3.6):条五色 + 底色三分类 + 今日线,内容 = v4 §11.2 + SRS §3.2 复述;色值取 TINT 单一来源 */
const BAR_COLORS: [string, string][] = [
  ["#1976d2", "计划条(蓝)"],
  ["#ed6c02", "进行中覆盖/实际(黄/橙)"],
  ["#2e7d32", "完成实际(绿)"],
  ["#d32f2f", "超期(红)"],
  ["#94A3B8", "父汇总条(灰蓝)"],
];

function Swatch({ color, label }: { color: string; label: string }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Box
        sx={{
          width: 24,
          height: 12,
          borderRadius: 0.5,
          bgcolor: color,
          border: "1px solid",
          borderColor: "divider",
        }}
      />
      <Typography variant="caption">{label}</Typography>
    </Stack>
  );
}

export function LegendPopover() {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  return (
    <>
      <Tooltip title="图例">
        <IconButton
          size="small"
          data-testid="gantt-legend"
          aria-label="图例"
          onClick={(e) => setAnchor(e.currentTarget)}
        >
          <PaletteOutlined fontSize="small" />
        </IconButton>
      </Tooltip>
      <Popover
        open={anchor !== null}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
      >
        <Stack spacing={1} sx={{ p: 2, maxWidth: 260 }}>
          <Typography variant="subtitle2">行底色(状态扫读)</Typography>
          <Swatch color={TINT.START} label="起点状态(如 To Do)" />
          {TINT.ACTIVE.map((c, i) => (
            <Swatch
              key={c}
              color={c}
              label={`进行中 · 浓淡档 ${i + 1}(${["浅", "较浅", "中", "较深", "深"][i]},按工序先后)`}
            />
          ))}
          <Swatch color={TINT.FINAL} label="终态(如 Done)" />
          <Typography variant="subtitle2" sx={{ mt: 1 }}>
            条色(计划/实际细读)
          </Typography>
          {BAR_COLORS.map(([c, label]) => (
            <Swatch key={c} color={c} label={label} />
          ))}
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
            蓝竖线 = 今天
          </Typography>
        </Stack>
      </Popover>
    </>
  );
}
