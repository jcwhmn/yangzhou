"use client";

import { Box } from "@mui/material";
import {
  type MilestoneMarker,
  type ReleaseMarker,
} from "../types";

/** YPJ-221(spec 0158 r2):Milestone/Release 时点竖线 + 顶部旗标带(方案 A′)。
 * 线 = 贯穿行区(条下层、pointerEvents none),虚=计划时点/实=已发生;旗 = 身份牌(名称+gate N/M+version)。
 * 空态:无可上图对象 → 不渲染旗标带;行位不受影响。 */
export const LAYER_H = 20; // 旗标层高(px;CP2 碰撞分层用)

type Marker = {
  kind: "ms" | "rel";
  id: string;
  name: string;
  version: string | null;
  date: string; // 线的日期(ms/rel-planned=targetDate,rel-released=releasedDate)
  dashed: boolean; // 虚=计划时点,实=已发生
  color: string; // MUI 调色板路径(sx 直解)
  bold: boolean; // ms in_progress 高亮
  gate: [number, number] | null;
};

const MS_COLOR: Record<string, string> = {
  planned: "text.disabled",
  in_progress: "primary.main",
  completed: "success.main",
};

export function MilestoneLines({
  ms,
  rel,
  xOf,
}: {
  ms: MilestoneMarker[];
  rel: ReleaseMarker[];
  xOf: (date: string | null) => number | null;
}) {
  const markers: (Marker & { x: number })[] = [];
  for (const m of ms) {
    if (m.status === "cancelled" || m.targetDate === null) continue;
    const x = xOf(m.targetDate);
    if (x === null) continue;
    markers.push({
      kind: "ms",
      id: m.milestoneId,
      name: m.name,
      version: null,
      date: m.targetDate,
      dashed: true,
      color: m.overdue && m.status === "planned" ? "error.main" : (MS_COLOR[m.status] ?? "text.disabled"),
      bold: m.status === "in_progress",
      gate:
        m.gateDone !== null && m.gateTotal !== null
          ? [m.gateDone, m.gateTotal]
          : null,
      x,
    });
  }
  for (const r of rel) {
    if (r.status === "released") {
      // released 只画 releasedDate 实线(targetDate 属历史,不画;拍板 0158 r2)
      if (r.releasedDate === null) continue;
      const x = xOf(r.releasedDate);
      if (x === null) continue;
      markers.push({
        kind: "rel",
        id: r.releaseId,
        name: r.name,
        version: r.version,
        date: r.releasedDate,
        dashed: false,
        color: "success.main",
        bold: false,
        gate: null,
        x,
      });
    } else {
      if (r.targetDate === null) continue;
      const x = xOf(r.targetDate);
      if (x === null) continue;
      markers.push({
        kind: "rel",
        id: r.releaseId,
        name: r.name,
        version: r.version,
        date: r.targetDate,
        dashed: true,
        color: r.overdue ? "error.main" : "primary.main",
        bold: false,
        gate: null,
        x,
      });
    }
  }
  if (markers.length === 0) return null;

  return (
    <>
      {/* 旗标带:CP1 单层;CP2 按 x 升序分层退让(20px/层,上限 3 层) */}
      <Box
        data-testid="gantt-flag-band"
        sx={{ position: "relative", height: LAYER_H }}
      >
        {markers.map((m) => (
          <Box
            key={`${m.kind}-${m.id}`}
            data-testid={`gantt-${m.kind}-flag-${m.id}`}
            sx={{
              position: "absolute",
              left: m.x + 4,
              top: 1,
              display: "inline-flex",
              alignItems: "center",
              gap: 0.5,
              border: "1px solid",
              borderColor: m.color,
              color: m.color,
              borderRadius: 1,
              px: 0.5,
              fontSize: 12,
              bgcolor: "background.paper",
              fontWeight: m.bold ? "bold" : 400,
              whiteSpace: "nowrap",
            }}
          >
            {m.name}
            {m.gate && (
              <Box component="span" sx={{ opacity: 0.85 }}>
                {m.gate[0]}/{m.gate[1]}
              </Box>
            )}
            {m.version && (
              <Box component="span" sx={{ opacity: 0.85 }}>
                v{m.version}
              </Box>
            )}
          </Box>
        ))}
      </Box>
      {/* 竖线:贯穿行区;DOM 序在行前 → 条不透明段自然盖线(条下层);不可点不拦 hover */}
      {markers.map((m) => (
        <Box
          key={`line-${m.kind}-${m.id}`}
          data-testid={`gantt-${m.kind}-line-${m.id}`}
          sx={{
            position: "absolute",
            top: 0,
            bottom: 0,
            left: m.x,
            width: 0,
            borderLeftWidth: 1,
            borderLeftStyle: m.dashed ? "dashed" : "solid",
            borderLeftColor: m.color,
            pointerEvents: "none",
          }}
        />
      ))}
    </>
  );
}
