"use client";

import { parseLocal } from "@/lib/dates";
import { DAY, type GanttRow } from "../types";

// 以下函数自 page.tsx 原样搬出(YPJ-249 纯搬家,零语义变化)

// YPJ-244(spec 0151 五):工作日历换算 JS 移植(口径同 WorkdayCalculator:每日窗线性,周六日照算,无午休)
export const hmToMin = (s: string) => {
  const [h, m] = s.split(":").map(Number);
  return h * 60 + m;
};

export function dueFromJs(
  startT: number,
  hours: number,
  ws: number,
  we: number,
  skipWeekends = false, // YPJ-226(spec 0163):工作制跳周末(与后端 WorkdayCalculator 同口径)
): number {
  if (hours <= 0) return startT;
  let day0 = new Date(startT);
  day0.setHours(0, 0, 0, 0);
  let cursor = Math.max(startT, +day0 + ws * 60000);
  let remain = Math.round(hours * 60) * 60000;
  for (;;) {
    if (skipWeekends && (day0.getDay() === 0 || day0.getDay() === 6)) {
      day0 = new Date(+day0 + DAY);
      cursor = +day0 + ws * 60000;
      continue;
    }
    const dayEnd = +day0 + we * 60000;
    if (cursor >= dayEnd) {
      day0 = new Date(+day0 + DAY);
      cursor = +day0 + ws * 60000;
      continue;
    }
    const avail = dayEnd - cursor;
    if (remain >= avail) {
      remain -= avail;
      if (remain === 0) return dayEnd;
      day0 = new Date(+day0 + DAY);
      cursor = +day0 + ws * 60000;
    } else return cursor + remain;
  }
}

export function estimateHoursOf(
  v: number | null,
  unit: string | null,
  ws: number,
  we: number,
): number | null {
  if (v == null) return null;
  const winH = (we - ws) / 60;
  return (unit ?? "hour") === "hour"
    ? v
    : unit === "day"
      ? v * winH
      : unit === "week"
        ? v * 7 * winH // SRS v4 §3.3(2026-11-21 拍板):week = 7 天(无 weekend 休息日语义)
        : v;
}

// YPJ-245(SRS §11.1):偏移徽标仅 FINAL 行(进行中超期由红计划条表达,零徽标)
// Δ = actualEnd − due(真实完成时刻 = 最近一次 FINAL);|Δ|<1h On time(中性)/ <24h ±Nh / ≥24h ±Nd(日历日差);提前绿/延期红;due 无值不标
export function offsetBadge(
  r: GanttRow,
): { label: string; color: string } | null {
  if (r.statusType !== "FINAL" || !r.dueDate || !r.actualEnd) return null;
  const dh = (+parseLocal(r.actualEnd) - +parseLocal(r.dueDate)) / 3600000;
  const day0 = (t: number) => {
    const d = new Date(t);
    d.setHours(0, 0, 0, 0);
    return +d;
  };
  if (Math.abs(dh) < 1) return { label: "On time", color: "text.secondary" };
  if (Math.abs(dh) < 24) {
    return {
      label: `${dh > 0 ? "+" : "-"}${Math.round(Math.abs(dh))}h`,
      color: dh > 0 ? "error.main" : "success.main",
    };
  }
  const days = Math.round(
    (day0(+parseLocal(r.actualEnd)) - day0(+parseLocal(r.dueDate))) / DAY,
  );
  return {
    label: `${days > 0 ? "+" : ""}${days}d`,
    color: days > 0 ? "error.main" : "success.main",
  };
}
