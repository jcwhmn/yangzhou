"use client";

import { useEffect, useMemo, useRef, type RefObject } from "react";
import { parseLocal } from "@/lib/dates";
import {
  DAY,
  GANTT_MIN_PPD,
  MAX_PPD,
  MIN_LABEL_PX,
  ZOOM_STEP,
  type GanttRow,
  type HeaderRow,
  type Tick,
} from "../types";
import { dueFromJs, estimateHoursOf } from "../utils/timeCalculations";

/** 时间轴缩放/视点与坐标换算(YPJ-249 自 page.tsx 原样搬出,零语义变化)。
 * summaryOf 经参数注入:轴范围与父行包络同源口径(SRS §7.3);zoom/center 状态留入口,本 hook 只封装行为。 */
export function useTimelineScale(opts: {
  rows: GanttRow[] | null;
  wd: [number, number] | null;
  zoom: number | null;
  setZoom: (z: number | null) => void;
  center: string;
  setCenter: (c: string) => void;
  containerW: number;
  scrollRef: RefObject<HTMLDivElement | null>;
  groupStartOf: (r: GanttRow) => string | null;
  summaryOf: (itemId: string) => [string, string] | null;
  saveView: () => void;
}) {
  const {
    rows,
    wd,
    zoom,
    setZoom,
    center,
    setCenter,
    containerW,
    scrollRef,
    groupStartOf,
    summaryOf,
    saveView,
  } = opts;
  // 轴(YPJ-219 spec 0140 v2):范围 = 数据锚定日界;pxPerDay 连续 ∈ [fit 全区间压一屏, MAX_PPD]
  const axis = useMemo(() => {
    const today = new Date();
    const rowsNow = rows ?? [];
    // 轴数据锚定与画条同口径(spec 0139/0140):父行自有日期忽略不画,取子孙排期并集;子行取自有日期
    const isParent = (r: GanttRow) =>
      rowsNow.some((x) => x.parentItemId === r.itemId);
    const wsm = wd?.[0] ?? 0; // SRS v4 §3.2:缺省 24h 全天窗
    const wem = wd?.[1] ?? 1440;
    const dates = rowsNow.flatMap((r) => {
      // YPJ-222(spec 0147):轴范围纳入实际起止——实际条超出计划区间时不被裁出画面
      if (!isParent(r)) {
        // YPJ-244(五):group 锚 + estimate 铺算末端纳入轴范围(case1/2 借锚点位/条尾)
        const gS =
          !r.startDate && !r.anchorDate && !r.dueDate && !r.actualStart
            ? groupStartOf(r)
            : null; // 借锚才进轴,防父自有日期泄轴(A32 用例)
        const eH = estimateHoursOf(r.estimateValue, r.estimateUnit, wsm, wem);
        const anchorT = r.actualStart ?? r.startDate ?? r.anchorDate ?? gS;
        const estEnd =
          eH && anchorT
            ? new Date(
                dueFromJs(+parseLocal(anchorT), eH, wsm, wem),
              ).toISOString()
            : null;
        return [
          r.startDate,
          r.dueDate,
          r.actualStart,
          r.actualEnd,
          r.anchorDate,
          gS,
          estEnd,
        ].filter(Boolean) as string[];
      }
      const s = summaryOf(r.itemId);
      return s ? [s[0], s[1]] : [];
    });
    const minT = dates.length
      ? Math.min(...dates.map((d) => +parseLocal(d)))
      : +today;
    const maxT = dates.length
      ? Math.max(...dates.map((d) => +parseLocal(d)))
      : +today;
    const start = new Date(minT);
    start.setHours(0, 0, 0, 0);
    const end = new Date(maxT);
    end.setHours(0, 0, 0, 0);
    const endT = +end + DAY; // 末日 24:00
    const spanDays = Math.max((endT - +start) / DAY, 1);
    const fit = containerW > 0 ? containerW / spanDays : 20;
    // 缺省态(zoom=null)= fit 但带下限(YPJ-234);显式 zoom 仍 clamp [fit, MAX]
    const pxPerDay = Math.min(
      Math.max(zoom ?? Math.max(fit, GANTT_MIN_PPD), fit),
      MAX_PPD,
    );
    const timelineW = spanDays * pxPerDay;
    // 轴 = 24h 钟表线性(2026-11-20 用户拍板:「满辐 48h、8h=1/6」;工作窗轴同日否决回退,A28 台账)
    const xOfT = (t: number) => ((t - +start) / DAY) * pxPerDay;

    // 两级刻度:细刻度(小时梯子 1/2/3/6/12h,取间距≥MIN_LABEL_PX 的最小步长);粗行(月/周/日)按段宽门控
    const rowsOut: HeaderRow[] = [];
    const pxPerHour = pxPerDay / 24;
    // 小时行:梯子取最小步长使间距≥MIN_LABEL_PX;无解(极度缩小时)整行丢弃,不吐过密标签
    const stepH = [1, 2, 3, 6, 12].find((s) => s * pxPerHour >= MIN_LABEL_PX);
    if (pxPerDay >= MIN_LABEL_PX && stepH) {
      const ticks: Tick[] = [];
      for (let h = 0; h <= spanDays * 24; h += stepH) {
        const d = new Date(+start + (h * DAY) / 24);
        const label = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
        ticks.push({
          x: xOfT(+d),
          label,
          key: `h${h}`,
        });
      }
      rowsOut.push({ gran: "hour", ticks });
    }
    // 日行:每天一格 MM-DD
    if (pxPerDay >= MIN_LABEL_PX) {
      const ticks: Tick[] = [];
      for (let t = +start; t < endT; t += DAY) {
        const d = new Date(t);
        ticks.push({
          x: xOfT(t),
          label: `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
          key: `d${t}`,
        });
      }
      rowsOut.push({ gran: "day", ticks });
    }
    // 周行:周一 Wnn
    if (pxPerDay * 7 >= 90) {
      const first = new Date(+start);
      const dow = (first.getDay() + 6) % 7;
      first.setDate(first.getDate() - dow);
      const ticks: Tick[] = [];
      for (let t = +first; t < endT; t += 7 * DAY) {
        const d = new Date(t);
        const week = Math.ceil(
          ((+d - +new Date(d.getFullYear(), 0, 1)) / DAY + 1) / 7,
        );
        ticks.push({
          x: xOfT(t),
          label: `W${String(week).padStart(2, "0")}`,
          key: `w${t}`,
        });
      }
      rowsOut.push({ gran: "week", ticks });
    }
    // 月行:日界月首 YYYY-MM
    if (pxPerDay * 30 >= 70) {
      const ticks: Tick[] = [];
      const cur = new Date(+start);
      cur.setDate(1);
      while (+cur < endT) {
        ticks.push({
          x: xOfT(+cur),
          label: `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`,
          key: `m${+cur}`,
        });
        cur.setMonth(cur.getMonth() + 1);
      }
      rowsOut.push({ gran: "month", ticks });
    }
    // 保底:极缩小时只留月行(抽稀)
    if (rowsOut.length === 0) {
      const stepDays = Math.ceil((MIN_LABEL_PX * 1.5) / pxPerDay / 30) * 30;
      const ticks: Tick[] = [];
      const cur = new Date(+start);
      cur.setDate(1);
      let i = 0;
      while (+cur < endT) {
        if (i % Math.max(stepDays / 30, 1) === 0)
          ticks.push({
            x: xOfT(+cur),
            label: `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`,
            key: `m${+cur}`,
          });
        cur.setMonth(cur.getMonth() + 1);
        i++;
      }
      rowsOut.push({ gran: "month", ticks });
    }
    // 最多 3 行:自顶(最粗)丢弃;渲染序 = 粗→细(月在最上,时在最底)
    rowsOut.reverse();
    while (rowsOut.length > 3) rowsOut.shift();

    return {
      start,
      endT,
      spanDays,
      pxPerDay,
      fit,
      timelineW,
      headerRows: rowsOut,
      todayX: xOfT(+today),
    };
  }, [rows, zoom, containerW, wd, groupStartOf]);

  // 缩放:锚点(anchorClientX,视口坐标)下的数据时刻保持不动;null 锚 = 视口中心
  const zoomTo = (newPpd: number, anchorClientX?: number) => {
    const el = scrollRef.current;
    if (!el) return;
    const anchorX = anchorClientX ?? el.clientWidth / 2;
    const dataDays = (el.scrollLeft + anchorX) / axis.pxPerDay;
    const clamped = Math.min(Math.max(newPpd, axis.fit), MAX_PPD);
    el.scrollLeft = Math.max(0, dataDays * clamped - anchorX);
    setZoom(clamped);
    setTimeout(saveView, 0);
  };

  // Ctrl+滚轮缩放(非被动监听以 preventDefault)
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      zoomTo(
        axis.pxPerDay * (e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP),
        e.clientX - rect.left,
      );
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [axis.pxPerDay, axis.fit, saveView]);

  // 视点日变化时滚动到视点日居中(缩放导致的 pxPerDay 变化不重触发,避免覆盖锚点滚动)
  // YPJ-229:轴起点变化(数据到达/切范围)后,同视点日也重居中——否则加载中设的视点日不重放;
  // 视点日=今日时居中「当前时刻」而非 00:00(否则 1 日窗下今日线偏出视口)
  const lastCenterRef = useRef("");
  const lastCenterStartRef = useRef<number | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !center) return;
    if (
      center === lastCenterRef.current &&
      +axis.start === lastCenterStartRef.current
    )
      return;
    lastCenterRef.current = center;
    lastCenterStartRef.current = +axis.start;
    const x =
      center === fmtDay(new Date())
        ? axis.todayX
        : ((+parseLocal(center) - +axis.start) / DAY) * axis.pxPerDay;
    el.scrollLeft = Math.max(0, x - el.clientWidth / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [center, axis.pxPerDay, axis.start, axis.todayX, rows]);

  // YPJ-222(spec 0147):今日线入视口——仅无视点日(首次打开)且今日线在视口外时滚至中部;每次挂载至多一次,不抢用户视点
  const didAutoScrollRef = useRef(false);
  useEffect(() => {
    if (didAutoScrollRef.current) return;
    if (rows === null || containerW <= 0) return;
    didAutoScrollRef.current = true;
    if (center) return;
    const tx = axis.todayX;
    if (tx < 0 || tx > axis.timelineW) return;
    const el = scrollRef.current;
    if (!el) return;
    if (tx < el.scrollLeft || tx > el.scrollLeft + el.clientWidth) {
      el.scrollLeft = Math.max(0, tx - el.clientWidth / 2);
      setTimeout(saveView, 0);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, containerW, center, axis.todayX, axis.timelineW]);

  const fmtDay = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const shiftCenter = (days: number) => {
    const base = center ? parseLocal(center) : new Date();
    base.setDate(base.getDate() + days);
    setCenter(fmtDay(base));
  };
  // 日窗预设 / 今日:视点日为中心,N 天撑满可视区
  // YPJ-229:数据/容器未就绪(rows 未到/容器未测宽)时点击 → 缩放静默丢失;改挂起,就绪后补放
  const pendingWinRef = useRef<number | null | undefined>(undefined);
  const applyDayWindow = (days: number | null) => {
    const target =
      days === null ? new Date() : center ? parseLocal(center) : new Date();
    lastCenterRef.current = ""; // 同值重设也要触发居中
    lastCenterStartRef.current = null;
    setCenter(fmtDay(target));
    if (days !== null && containerW > 0) setZoom(containerW / days);
    else if (days === null) setZoom(null);
  };
  const goToDayWindow = (days: number | null) => {
    if (rows === null || containerW <= 0) pendingWinRef.current = days;
    else {
      pendingWinRef.current = undefined; // 直接生效路径清挂起,防切范围后补放旧值
      applyDayWindow(days);
    }
  };
  // YPJ-234 案乙:今日 = 只居中不重置缩放;同值重设也要触发居中(清 lastCenter refs)
  const centerOnToday = () => {
    lastCenterRef.current = "";
    lastCenterStartRef.current = null;
    setCenter(fmtDay(new Date()));
  };
  useEffect(() => {
    if (rows === null || containerW <= 0) return;
    const p = pendingWinRef.current;
    if (p === undefined) return;
    pendingWinRef.current = undefined;
    applyDayWindow(p);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, containerW]);

  const xOf = (date: string | null) =>
    date === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            ((+parseLocal(date) - +axis.start) / DAY) * axis.pxPerDay,
          ),
        );

  // YPJ-244(五):ms 时间戳版 x 坐标(estimate 铺算末端/group 锚等非字符串时刻)
  const xOfT = (t: number) =>
    Math.min(
      axis.timelineW,
      Math.max(0, ((t - +axis.start) / DAY) * axis.pxPerDay),
    );

  // YPJ-218(spec 0139)B3:条宽含当天——仅 date-only +1 天;datetime 右端=精确 due
  const xEndOf = (due: string | null) =>
    due === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            ((+parseLocal(due) +
              (/^\d{4}-\d{2}-\d{2}$/.test(due) ? DAY : 0) -
              +axis.start) /
              DAY) *
              axis.pxPerDay,
          ),
        );

  return {
    axis,
    zoomTo,
    goToDayWindow,
    shiftCenter,
    centerOnToday,
    xOf,
    xOfT,
    xEndOf,
  };
}
