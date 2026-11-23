"use client";

import { useEffect, useMemo, useRef, type RefObject } from "react";
import { parseLocal } from "@/lib/dates";
import {
  DAY,
  GANTT_MIN_PPD,
  MAX_PPD,
  MIN_LABEL_PX,
  PPD_LS,
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
  wd: [number, number, boolean] | null;
  zoom: number | null;
  setZoom: (z: number | null) => void;
  center: string;
  setCenter: (c: string) => void;
  containerW: number;
  scrollRef: RefObject<HTMLDivElement | null>;
  groupStartOf: (r: GanttRow) => string | null;
  summaryOf: (itemId: string) => [string, string] | null;
  saveView: () => void;
  /** YPJ-221 CP2b:ms/rel marker 日期(轴纳入,避免出界钳边假位移) */
  markerDates: string[];
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
    markerDates,
  } = opts;
  // 轴(YPJ-219 spec 0140 v2):范围 = 数据锚定日界;pxPerDay 连续 ∈ [fit 全区间压一屏, MAX_PPD]
  const axis = useMemo(() => {
    const today = new Date();
    const rowsNow = rows ?? [];
    const skipWk = wd?.[2] ?? false; // YPJ-226(spec 0163):工作制=跳周末(折叠几何)
    // 轴数据锚定与画条同口径(spec 0139/0140):父行自有日期忽略不画,取子孙排期并集;子行取自有日期
    const isParent = (r: GanttRow) =>
      rowsNow.some((x) => x.parentItemId === r.itemId);
    const wsm = wd?.[0] ?? 0; // SRS v4 §3.2:缺省 24h 全天窗;YPJ-226 工作制窗口正交(全天窗+跳周末=只折周末)
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
    const allT = [
      ...dates.map((d) => +parseLocal(d)),
      ...markerDates.map((d) => +parseLocal(d)),
    ];
    const minT = allT.length ? Math.min(...allT) : +today;
    const maxT = allT.length ? Math.max(...allT) : +today;
    const start = new Date(minT);
    start.setHours(0, 0, 0, 0);
    const end = new Date(maxT);
    end.setHours(0, 0, 0, 0);
    const endT = +end + DAY; // 末日 24:00
    // YPJ-226(spec 0163):工作制轴=折叠几何——非工作时段(周末+窗)零宽度。
    // segs = 逐工作日的窗区间(ms 自轴起);cum[i] = 第 i 段前的累计工作毫秒;W = 总工作毫秒。
    const segs: { s: number; e: number }[] = [];
    if (skipWk) {
      for (let t = +start; t < endT; t += DAY) {
        const dow = new Date(t).getDay();
        if (dow === 0 || dow === 6) continue;
        segs.push({ s: t + wsm * 60000, e: t + wem * 60000 });
      }
    }
    const cum: number[] = [];
    let workMs = 0;
    for (const g of segs) {
      cum.push(workMs);
      workMs += g.e - g.s;
    }
    // 缩放单位:wall=pxPerDay(每钟表日)/ work=每工作日(窗时长)。两模式共用同一钳界常量。
    const winMs = (wem - wsm) * 60000 || DAY;
    const spanDays = skipWk
      ? Math.max(workMs / winMs, 1) // 工作日数
      : Math.max((endT - +start) / DAY, 1);
    const fit = containerW > 0 ? containerW / spanDays : 20;
    const pxPerDay = Math.min(
      Math.max(zoom ?? Math.max(fit, GANTT_MIN_PPD), fit),
      MAX_PPD,
    );
    const timelineW = spanDays * pxPerDay;
    // 唯一坐标函数:t→x。wall=钟表线性(原式);work=累计工作毫秒×pxPerMs(段外鍲到邻段)。
    const pxPerMs = pxPerDay / winMs;
    const tToX = (t: number): number => {
      if (!skipWk || segs.length === 0) return ((t - +start) / DAY) * pxPerDay;
      const tc = Math.min(
        Math.max(t, segs[0]?.s ?? +start),
        segs[segs.length - 1]?.e ?? endT,
      );
      let lo = 0;
      let hi = segs.length - 1;
      while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (segs[mid].s <= tc) lo = mid;
        else hi = mid - 1;
      }
      const g = segs[lo];
      const inSeg = tc >= g.s ? tc - g.s : 0;
      return (cum[lo] + inSeg) * pxPerMs;
    };

    // 刻度行(YPJ-241 分层;YPJ-265 真值表 §9.3.1):粗→细最多 4 行;日行任何密度必渲染(抽稀不删行)
    const rowsOut: HeaderRow[] = [];
    const monthVisible = pxPerDay * (skipWk ? 21 : 30) >= 70;
    // 时行(YPJ-226 切片3 spec 0163):梯子 5–720 分钟;YPJ-265 门控 44→24px(30min 刻度下放至 pxPerDay≥1152)
    const pxPerMin = pxPerDay / (skipWk ? wem - wsm || 1440 : 1440);
    const winMin = skipWk ? wem - wsm : Infinity;
    const stepMin = [5, 10, 15, 20, 30, 60, 120, 180, 360, 720].find(
      (s) => s * pxPerMin >= 24 && s <= winMin,
    );
    if (stepMin) {
      const ticks: Tick[] = [];
      if (skipWk) {
        for (const g of segs) {
          for (let m = 0; m < wem - wsm; m += stepMin) {
            const t = g.s + m * 60000;
            const d = new Date(t);
            ticks.push({
              x: tToX(t),
              label: `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`,
              key: `h${t}`,
            });
          }
        }
      } else {
        const spanMin = spanDays * 1440;
        for (let m = 0; m <= spanMin; m += stepMin) {
          const d = new Date(+start + m * 60000);
          ticks.push({
            x: tToX(+d),
            label: `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`,
            key: `h${m}`,
          });
        }
      }
      rowsOut.push({ gran: "hour", ticks });
    }
    // 日行(YPJ-265):任何密度必渲染;有月行→纯日号(1/4/7),无月行→MM-DD 保月信息;密度不足抽稀(步长=能容标签的最小天数)
    {
      const labelW = monthVisible ? 18 : 44;
      const dayStep = Math.max(1, Math.ceil(labelW / pxPerDay));
      const dayLabel = (d: Date) =>
        monthVisible
          ? String(d.getDate())
          : `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
      const ticks: Tick[] = [];
      if (skipWk) {
        let n = 0;
        for (const g of segs) {
          if (n % dayStep === 0) {
            const d = new Date(g.s);
            ticks.push({ x: tToX(g.s), label: dayLabel(d), key: `d${g.s}` });
          }
          n += 1;
        }
      } else {
        for (let t = +start; t < endT; t += dayStep * DAY) {
          const d = new Date(t);
          ticks.push({ x: tToX(t), label: dayLabel(d), key: `d${t}` });
        }
      }
      rowsOut.push({ gran: "day", ticks });
    }
    // 周行(仅 wall;work 周末零宽度无周界):周一 Wnn
    if (!skipWk && pxPerDay * 7 >= 90) {
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
          x: tToX(t),
          label: `W${String(week).padStart(2, "0")}`,
          key: `w${t}`,
        });
      }
      rowsOut.push({ gran: "week", ticks });
    }
    // 月行:wall=日界月首 / work=月首工作日窗起点(工作日/月≈21)
    const genMonthTicks = (every: number): Tick[] => {
      const ticks: Tick[] = [];
      const cur = new Date(+start);
      cur.setDate(1);
      let i = 0;
      while (+cur < endT) {
        if (i % Math.max(every, 1) === 0) {
          let tickT: number | null;
          if (skipWk) {
            const probe = new Date(+cur);
            while (
              +probe < endT &&
              probe.getMonth() === cur.getMonth() &&
              (probe.getDay() === 0 || probe.getDay() === 6)
            )
              probe.setDate(probe.getDate() + 1);
            tickT =
              probe.getMonth() === cur.getMonth() && +probe < endT
                ? +probe + wsm * 60000
                : null;
          } else {
            tickT = +cur;
          }
          if (tickT !== null)
            ticks.push({
              x: tToX(tickT),
              label: `${cur.getFullYear()}-${String(cur.getMonth() + 1).padStart(2, "0")}`,
              key: `m${+cur}`,
            });
        }
        cur.setMonth(cur.getMonth() + 1);
        i++;
      }
      return ticks;
    };
    if (monthVisible) {
      rowsOut.push({ gran: "month", ticks: genMonthTicks(1) });
    }
    // 年行(YPJ-226 切片3 spec 0163):极缩小时 YYYY;wall=元旦 / work=年内首个工作日窗起点;单点无意义不渲染
    if (pxPerDay * 365 <= MIN_LABEL_PX) {
      const y0 = new Date(+start).getFullYear();
      const y1 = new Date(endT).getFullYear();
      const ticks: Tick[] = [];
      for (let y = y0; y <= y1; y++) {
        let tickT: number;
        if (skipWk) {
          const probe = new Date(y, 0, 1);
          while (probe.getDay() === 0 || probe.getDay() === 6)
            probe.setDate(probe.getDate() + 1);
          tickT = +probe + wsm * 60000;
        } else {
          tickT = +new Date(y, 0, 1);
        }
        if (tickT >= +start && tickT < endT)
          ticks.push({ x: tToX(tickT), label: `${y}`, key: `y${y}` });
      }
      if (ticks.length > 1) rowsOut.push({ gran: "year", ticks });
    }
    // 保底:极缩小时只留月行(抽稀)
    if (rowsOut.length === 0) {
      const stepDays = Math.ceil((MIN_LABEL_PX * 1.5) / pxPerDay / 30) * 30;
      rowsOut.push({
        gran: "month",
        ticks: genMonthTicks(Math.max(stepDays / 30, 1)),
      });
    }
    // 最多 4 行(YPJ-226 切片3:年/月/周/日/时取最粗四层);自顶丢弃;渲染序 = 粗→细
    rowsOut.reverse();
    while (rowsOut.length > 4) rowsOut.shift();

    return {
      start,
      endT,
      spanDays,
      pxPerDay,
      fit,
      timelineW,
      headerRows: rowsOut,
      todayX: tToX(+today),
      tToX,
    };
  }, [rows, zoom, containerW, wd, groupStartOf, markerDates]);

  // spec 0165 SRS §3.5:ppd 跨会话记忆——当前密度落 localStorage(fit 也属显式视窗选择),读取失败静默
  useEffect(() => {
    if (containerW <= 0) return; // 首测宽前的 fit 值不写
    try {
      localStorage.setItem(PPD_LS, String(axis.pxPerDay));
    } catch {
      /* 隐私模式等:无记忆可用,静默 */
    }
  }, [axis.pxPerDay, containerW]);

  // 缩放:锚点(anchorClientX,视口坐标)下的数据点保持不动;null 锚 = 视口中心。
  // px 比例缩放(模式无关):x 轴线性(pxPerMs 恒定),直接按比例缩 scrollLeft,无需逆映射。
  const zoomTo = (newPpd: number, anchorClientX?: number) => {
    const el = scrollRef.current;
    if (!el) return;
    const anchorX = anchorClientX ?? el.clientWidth / 2;
    const dataX = el.scrollLeft + anchorX;
    const clamped = Math.min(Math.max(newPpd, axis.fit), MAX_PPD);
    el.scrollLeft = Math.max(0, (dataX * clamped) / axis.pxPerDay - anchorX);
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
        : axis.tToX(+parseLocal(center));
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
  // YPJ-234 案乙:今日 = 只居中不重置缩放;同值重设也要触发居中(清 lastCenter refs)
  const centerOnToday = () => {
    lastCenterRef.current = "";
    lastCenterStartRef.current = null;
    setCenter(fmtDay(new Date()));
  };
  // 坐标函数:统一走轴的 tToX(wall=钟表线性 / work=折叠累计,YPJ-226 spec 0163)
  const xOf = (date: string | null) =>
    date === null
      ? null
      : Math.min(axis.timelineW, Math.max(0, axis.tToX(+parseLocal(date))));

  // YPJ-244(五):ms 时间戳版 x 坐标(estimate 铺算末端/group 锚等非字符串时刻)
  const xOfT = (t: number) =>
    Math.min(axis.timelineW, Math.max(0, axis.tToX(t)));

  // YPJ-218(spec 0139)B3:条宽含当天——仅 date-only +1 天;datetime 右端=精确 due
  const xEndOf = (due: string | null) =>
    due === null
      ? null
      : Math.min(
          axis.timelineW,
          Math.max(
            0,
            axis.tToX(
              +parseLocal(due) + (/^\d{4}-\d{2}-\d{2}$/.test(due) ? DAY : 0),
            ),
          ),
        );

  return {
    axis,
    zoomTo,
    shiftCenter,
    centerOnToday,
    xOf,
    xOfT,
    xEndOf,
  };
}
