"use client";

import { Box, Tooltip } from "@mui/material";
import Link from "next/link";
import { type MilestoneMarker, type ReleaseMarker } from "../types";

/** YPJ-221(spec 0158 r2):Milestone/Release 时点竖线 + 顶部旗标带(方案 A′)。
 * 线 = 贯穿行区(条下层、pointerEvents none),虚=计划时点/实=已发生;
 * 旗 = 身份牌(名称+gate N/M+version),Tooltip 全信息,点击跳管理页。
 * 旗标碰撞退让(确定性,无测量):按 x 升序贪心分层(20px/层),标签宽按字数估算;
 * 上限 3 层,超限仅线(旗标牺牲,tooltip 无宿主)。全空 → 带不渲染,行位不受影响。 */
const LAYER_H = 20;
const LAYER_MAX = 3;
const FLAG_GAP = 4; // 旗与线的水平间距
const CHAR_W = 12; // 标签字宽估算

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
  tooltipLines: string[];
};

const MS_COLOR: Record<string, string> = {
  planned: "text.disabled",
  in_progress: "primary.main",
  completed: "success.main",
};
const STATUS_ZH: Record<string, string> = {
  planned: "已规划",
  in_progress: "进行中",
  completed: "已完成",
  cancelled: "已取消",
  released: "已发布",
};

function daysText(days: number | null, overdue: boolean): string | null {
  if (overdue) return "已超期";
  if (days === null) return null;
  return days >= 0 ? `剩 ${days} 天` : `已超 ${-days} 天`;
}

export function MilestoneLines({
  ms,
  rel,
  xOf,
  projectKey,
}: {
  ms: MilestoneMarker[];
  rel: ReleaseMarker[];
  xOf: (date: string | null) => number | null;
  projectKey: string;
}) {
  const markers: Marker[] = [];
  for (const m of ms) {
    if (m.status === "cancelled" || m.targetDate === null) continue;
    const t = daysText(m.daysToTarget, m.overdue);
    const lines = [
      `状态:${STATUS_ZH[m.status] ?? m.status}`,
      `目标日期:${m.targetDate}`,
      ...(t ? [t] : []),
      ...(m.gateDone !== null && m.gateTotal !== null
        ? [`关口就绪度:${m.gateDone}/${m.gateTotal}`]
        : []),
    ];
    markers.push({
      kind: "ms",
      id: m.milestoneId,
      name: m.name,
      version: null,
      date: m.targetDate,
      dashed: true,
      color:
        m.overdue && m.status === "planned"
          ? "error.main"
          : (MS_COLOR[m.status] ?? "text.disabled"),
      bold: m.status === "in_progress",
      gate:
        m.gateDone !== null && m.gateTotal !== null
          ? [m.gateDone, m.gateTotal]
          : null,
      tooltipLines: lines,
    });
  }
  for (const r of rel) {
    if (r.status === "released") {
      // released 只画 releasedDate 实线(targetDate 属历史,不画;拍板 0158 r2)
      if (r.releasedDate === null) continue;
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
        tooltipLines: [
          `状态:已发布`,
          ...(r.targetDate ? [`目标发布日:${r.targetDate}(历史)`] : []),
          `实际发布日:${r.releasedDate}`,
          ...(r.stage ? [`stage:${r.stage}`] : []),
        ],
      });
    } else {
      if (r.targetDate === null) continue;
      const t = daysText(null, r.overdue);
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
        tooltipLines: [
          `状态:已规划`,
          `目标发布日:${r.targetDate}`,
          ...(t ? [t] : []),
          ...(r.stage ? [`stage:${r.stage}`] : []),
        ],
      });
    }
  }
  if (markers.length === 0) return null;

  // 分层:x 升序贪心;旗宽估算 = 名字 + gate 章 + version + 内边距
  type Placed = Marker & { x: number; layer: number | null };
  const placed: Placed[] = markers
    .map((m) => ({ ...m, x: xOf(m.date) ?? 0, layer: null as number | null }))
    .sort((a, b) => a.x - b.x); // 同 x 稳定序(ms 先于 rel,建序先后)
  const layerRight: number[] = [];
  for (const m of placed) {
    const left = m.x + FLAG_GAP;
    const w =
      m.name.length * CHAR_W + (m.gate ? 44 : 0) + (m.version ? 40 : 0) + 16;
    let li = layerRight.findIndex((right) => right <= left);
    if (li === -1 && layerRight.length < LAYER_MAX) {
      layerRight.push(0);
      li = layerRight.length - 1;
    }
    if (li === -1) continue; // 超 3 层:仅线,旗牺牲
    layerRight[li] = left + w;
    m.layer = li;
  }
  if (layerRight.length === 0) return null; // 全部超限:仅线,带也不渲染

  return (
    <>
      {/* 旗标带:层高 20px,高=占用层数 */}
      <Box
        data-testid="gantt-flag-band"
        sx={{ position: "relative", height: layerRight.length * LAYER_H }}
      >
        {placed
          .filter((m) => m.layer !== null)
          .map((m) => (
            <Tooltip
              key={`${m.kind}-${m.id}`}
              title={
                <Box sx={{ fontSize: 12 }}>
                  {m.tooltipLines.map((l) => (
                    <Box key={l}>{l}</Box>
                  ))}
                </Box>
              }
            >
              <Link
                href={`/p/${projectKey}/${m.kind === "ms" ? "milestones" : "releases"}`}
                style={{
                  position: "absolute",
                  left: m.x + FLAG_GAP,
                  top: m.layer! * LAYER_H + 1,
                  textDecoration: "none",
                }}
              >
                <Box
                  data-testid={`gantt-${m.kind}-flag-${m.id}`}
                  sx={{
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
              </Link>
            </Tooltip>
          ))}
      </Box>
      {/* 竖线:贯穿行区;DOM 序在行前 → 条不透明段自然盖线(条下层);不可点不拦 hover */}
      {placed.map((m) => (
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
