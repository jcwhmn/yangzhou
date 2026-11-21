"use client";

import {
  Autocomplete,
  Box,
  Button,
  Chip,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
} from "react";
import { api } from "@/lib/api";
import { parseLocal, fmtDateTime } from "@/lib/dates";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";

type GanttRow = {
  itemId: string;
  parentItemId: string | null;
  number: string;
  title: string;
  startDate: string | null;
  dueDate: string | null;
  statusName: string;
  statusType: string | null;
  overdue: boolean;
  blocked: boolean;
  estimateValue: number | null;
  estimateUnit: string | null;
  depth: number;
  actualStart: string | null;
  actualEnd: string | null;
  anchorDate: string | null; // YPJ-244(spec 0151):依赖锚点=多前置最晚 due(无 due 退 start);null=无可用前置时间
  assigneeName: string | null;
  checklistDone: number | null;
  checklistTotal: number | null;
};

const DAY = 24 * 60 * 60 * 1000;

// YPJ-244(spec 0151 五):工作日历换算 JS 移植(口径同 WorkdayCalculator:每日窗线性,周六日照算,无午休)
const hmToMin = (s: string) => {
  const [h, m] = s.split(":").map(Number);
  return h * 60 + m;
};

function dueFromJs(
  startT: number,
  hours: number,
  ws: number,
  we: number,
): number {
  if (hours <= 0) return startT;
  let day0 = new Date(startT);
  day0.setHours(0, 0, 0, 0);
  let cursor = Math.max(startT, +day0 + ws * 60000);
  let remain = Math.round(hours * 60) * 60000;
  for (;;) {
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

function estimateHoursOf(
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
function offsetBadge(r: GanttRow): { label: string; color: string } | null {
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

// YPJ-219 v2(spec 0140):轴 = 数据锚定(首日 00:00 → 末日 24:00),连续缩放,无语义档位
const TITLE_W_DEFAULT = 280; // 标题列默认宽(=236 出厂值;237 双击重置回落此值)
const TITLE_W_MIN = 120;
const TITLE_W_LS = "yz-gantt-title-w"; // 237:标题列宽 localStorage 记忆键
const MAX_PPD = 2400; // 缩放上限:1日窗承诺在内容宽 ≤2680 成立(YPJ-230;1920 屏内容 1386 原被 720 夹成 ~1.9 天)
const GANTT_MIN_PPD = 3; // 缺省视窗 pxPerDay 下限(YPJ-234 拍板案 B 修订:宽区间不把条压没;1 天 ≈ 3px、1 月 ≈ 91px 可读;只约束缺省态,显式缩放仍可压破至 fit)
const MIN_LABEL_PX = 44; // 刻度标签最小间距
const ZOOM_STEP = 1.5; // ± 按钮步进系数

type Tick = { x: number; label: string; key: string };
type HeaderRow = { gran: "month" | "week" | "day" | "hour"; ticks: Tick[] };

// estimateUnit 封闭枚举(hour/day/week)的短标(YPJ-204)
const EST_UNIT_SHORT: Record<string, string> = {
  hour: "h",
  day: "d",
  week: "w",
};

/** 单项目树形甘特(只读)。条 = start→due;只有 due 画里程碑菱形;今日线;未排期折叠组。 */
export default function GanttPage() {
  const { key } = useParams<{ key: string }>();
  const [rows, setRows] = useState<GanttRow[] | null>(null);
  const [wd, setWd] = useState<[number, number] | null>(null); // 五:工作日历窗(分钟;默认 09:00-18:00)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  // YPJ-245(SRS §10.6):已完成折叠组,默认收起(会话态,不持久)
  const [showCompleted, setShowCompleted] = useState(false);
  const [showUnscheduled, setShowUnscheduled] = useState(false);
  // v2 工况:zoom(pxPerDay,null=自适应全区间)+ 视点日(日窗预设/今日的中心)+ 滚动位,sessionStorage 持久化
  // YPJ-220:范围 scope/scopeIds 同存同恢复(旧值缺省补 board)
  const savedView = (() => {
    try {
      return JSON.parse(sessionStorage.getItem("gantt.view") ?? "{}") as {
        zoom?: number;
        center?: string;
        scroll?: number;
        scope?: string;
        scopeIds?: string[];
      };
    } catch {
      return {};
    }
  })();
  const [zoom, setZoom] = useState<number | null>(savedView.zoom ?? null);
  const [center, setCenter] = useState(savedView.center ?? "");
  const [scope, setScope] = useState(savedView.scope ?? "board");
  const [scopeIds, setScopeIds] = useState<string[]>(savedView.scopeIds ?? []);
  const [containerW, setContainerW] = useState(0); // 可视宽(内容至少撑满一屏)
  // 237(spec 0145):标题列宽可拖拽,localStorage 记忆;无值/越界回落默认
  const [titleW, setTitleW] = useState(() => {
    if (typeof window === "undefined") return TITLE_W_DEFAULT;
    const v = Number(window.localStorage.getItem(TITLE_W_LS));
    return Number.isFinite(v) && v >= TITLE_W_MIN ? v : TITLE_W_DEFAULT;
  });
  const [splitting, setSplitting] = useState(false);
  const splitRef = useRef<{ startX: number; startW: number; w: number } | null>(
    null,
  );
  const scrollRef = useRef<HTMLDivElement>(null);
  const loadSeqRef = useRef(0); // 响应序号守卫:切范围后慢响应晚到不覆盖新状态

  // 夹取:仅交互路径调用(此时 containerW 已实测;初值不夹 max,避免测量前误夹 120)
  const clampTitleW = (w: number) =>
    Math.min(
      Math.max(w, TITLE_W_MIN),
      Math.max(TITLE_W_MIN, Math.floor(containerW / 2)),
    );
  const persistTitleW = (w: number) => {
    const c = clampTitleW(w);
    setTitleW(c);
    window.localStorage.setItem(TITLE_W_LS, String(c));
  };
  const onSplitMouseDown = (e: ReactMouseEvent<HTMLDivElement>) => {
    e.preventDefault();
    splitRef.current = { startX: e.clientX, startW: titleW, w: titleW };
    setSplitting(true);
    const move = (ev: MouseEvent) => {
      const s = splitRef.current;
      if (!s) return;
      s.w = clampTitleW(s.startW + ev.clientX - s.startX);
      setTitleW(s.w);
    };
    const up = () => {
      if (splitRef.current)
        window.localStorage.setItem(TITLE_W_LS, String(splitRef.current.w));
      splitRef.current = null;
      setSplitting(false);
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  // 范围二级选项(group/milestone/release;列表 API 均返回裸数组)
  const [groupOpts, setGroupOpts] = useState<
    { groupId: string; name: string }[]
  >([]);
  const [msOpts, setMsOpts] = useState<{ milestoneId: string; name: string }[]>(
    [],
  );
  const [relOpts, setRelOpts] = useState<{ releaseId: string; name: string }[]>(
    [],
  );

  const load = useCallback(async () => {
    // 未选完不发请求:group 空多选 / milestone、release 未选中 → 直接空态
    if (
      scope === "group"
        ? scopeIds.length === 0
        : (scope === "milestone" || scope === "release") &&
          scopeIds.length !== 1
    ) {
      setRows([]);
      return;
    }
    const qs = new URLSearchParams();
    if (scope !== "board") qs.set("scope", scope);
    if (scopeIds.length > 0) qs.set("scopeIds", scopeIds.join(","));
    const seq = ++loadSeqRef.current;
    const q = qs.toString();
    const [data, proj] = await Promise.all([
      api<{ rows: GanttRow[] }>(
        `/api/projects/${key}/gantt${q ? `?${q}` : ""}`,
      ),
      api<{ workdayStart: string; workdayEnd: string }>(`/api/projects/${key}`),
    ]);
    if (seq !== loadSeqRef.current) return; // 已有更新的范围请求,丢弃过期响应
    setRows(data.rows);
    setWd([hmToMin(proj.workdayStart), hmToMin(proj.workdayEnd)]);
  }, [key, scope, scopeIds]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 二级选项一次拉取(失败静默:选择器留空,不阻塞甘特)
  useEffect(() => {
    api<{ groupId: string; name: string }[]>(`/api/projects/${key}/groups`)
      .then(setGroupOpts)
      .catch(() => {});
    api<{ milestoneId: string; name: string }[]>(
      `/api/projects/${key}/milestones`,
    )
      .then(setMsOpts)
      .catch(() => {});
    api<{ releaseId: string; name: string }[]>(`/api/projects/${key}/releases`)
      .then(setRelOpts)
      .catch(() => {});
  }, [key]);

  const changeScope = (next: string) => {
    setScope(next);
    setScopeIds([]); // 切范围清空二级选择(spec 0141)
  };

  // YPJ-244(spec 0151 五):group 开始锚=父链上溯最近祖先 startDate(全链无=null)
  const rowById = useMemo(
    () => new Map((rows ?? []).map((r) => [r.itemId, r] as const)),
    [rows],
  );
  const groupStartOf = useCallback(
    (r: GanttRow): string | null => {
      let p = r.parentItemId;
      while (p) {
        const parent = rowById.get(p);
        if (!parent) break;
        if (parent.startDate) return parent.startDate;
        p = parent.parentItemId;
      }
      return null;
    },
    [rowById],
  );

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

  // 测可视宽(含窗口缩放重算)
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const measure = () => setContainerW(el.clientWidth);
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [rows]);

  const saveView = useCallback(() => {
    if (scrollRef.current)
      sessionStorage.setItem(
        "gantt.view",
        JSON.stringify({
          zoom,
          center,
          scroll: scrollRef.current.scrollLeft,
          scope,
          scopeIds,
        }),
      );
  }, [zoom, center, scope, scopeIds]);

  // 滚动位置持久化;范围/缩放/视点变化也落盘(不滚也不丢工况,YPJ-220)
  useEffect(() => {
    saveView();
  }, [saveView]);
  // 存档恢复只做一次(restoredRef 门,YPJ-234):原先依赖 [saveView, rows] 重跑,每次缩放/切范围后都把挂载时旧 scrollLeft 拍回去 → 缩放中心锚点失效
  const restoredRef = useRef(false);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (!restoredRef.current) {
      restoredRef.current = true;
      if (savedView.scroll) el.scrollLeft = savedView.scroll;
    }
    el.addEventListener("scroll", saveView);
    return () => el.removeEventListener("scroll", saveView);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveView, rows]);

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

  // SRS v4 §7.2(2026-11-21 拍板;第三方评审 P0-1 修订):父汇总条 = 当前过滤后可见子孙**叶行**
  // 渲染元素(计划条/计划点/实际条/实际点)的时间并集——从渲染结果反推,不从字段拼凑:
  //   计划条 = 锚(start/依赖锚/group 借锚)→(due 端[date-only 含当天] ?? 锚+Estimate 钟表长);仅锚无长度 → 计划点(零宽)
  //   实际条 = actualStart→(actualEnd ?? 今天);仅 actualEnd → 完成点(零宽,直建 FINAL)
  // 各级父行自有日期与自有状态史作废(§4.3),只继续下探;与排序锚/fit 同源口径(§7.3)。
  function summaryOf(itemId: string): [string, string] | null {
    let min: number | null = null;
    let max: number | null = null;
    const consider = (l: number, r: number) => {
      if (min === null || l < min) min = l;
      if (max === null || r > max) max = r;
    };
    const visit = (id: string) => {
      for (const x of rows ?? []) {
        if (x.parentItemId !== id) continue;
        if ((rows ?? []).some((k) => k.parentItemId === x.itemId)) {
          visit(x.itemId);
          continue;
        }
        const wsm = wd?.[0] ?? 0;
        const wem = wd?.[1] ?? 1440;
        const estH = estimateHoursOf(x.estimateValue, x.estimateUnit, wsm, wem);
        // 计划层元素
        const anchorRaw = x.startDate ?? x.anchorDate ?? groupStartOf(x);
        if (anchorRaw !== null) {
          const a = +parseLocal(anchorRaw);
          const dueEnd =
            x.dueDate !== null
              ? +parseLocal(x.dueDate) +
                (/^\d{4}-\d{2}-\d{2}$/.test(x.dueDate) ? DAY : 0)
              : null;
          const estEnd = estH !== null ? dueFromJs(a, estH, wsm, wem) : null;
          if (dueEnd !== null || estEnd !== null)
            consider(a, Math.max(a, dueEnd ?? estEnd!));
          else consider(a, a); // 计划点:零宽(case1 借锚点 / case3b)
        } else if (x.dueDate !== null) {
          // due-only 菱形(历史数据,Q11 废除后无新增):零宽点位参与包络
          const d = +parseLocal(x.dueDate);
          consider(d, d);
        }
        // 实际层元素
        if (x.actualStart !== null) {
          const s = +parseLocal(x.actualStart);
          const e =
            x.actualEnd !== null ? +parseLocal(x.actualEnd) : Date.now();
          consider(s, Math.max(s, e));
        } else if (x.actualEnd !== null) {
          const e = +parseLocal(x.actualEnd);
          consider(e, e); // 完成点:零宽(直建 FINAL,SRS §6.4)
        }
      }
    };
    visit(itemId);
    return min === null || max === null
      ? null
      : [new Date(min).toISOString(), new Date(max).toISOString()];
  }

  // YPJ-222(spec 0147):折叠徽标 N = 该父的全部后代数(任一深度)
  function descendantsOf(itemId: string): number {
    let n = 0;
    const visit = (id: string) => {
      for (const x of rows ?? []) {
        if (x.parentItemId !== id) continue;
        n++;
        visit(x.itemId);
      }
    };
    visit(itemId);
    return n;
  }

  // YPJ-222(spec 0147):条上 hover 详情卡;起止显时分、工时按小时(YPJ-244:数据含时分)
  function hoverCard(r: GanttRow) {
    const estH = estimateHoursOf(
      r.estimateValue,
      r.estimateUnit,
      wd?.[0] ?? 0,
      wd?.[1] ?? 1440,
    );
    const actH =
      r.actualStart && r.actualEnd
        ? Math.max(
            0,
            (+parseLocal(r.actualEnd) - +parseLocal(r.actualStart)) / 3600000,
          )
        : null;
    const fmtH = (h: number) => `${+h.toFixed(1)}h`;
    return (
      <Stack spacing={0.25}>
        <Typography variant="caption" sx={{ fontWeight: 600 }}>
          {r.number} {r.title}
        </Typography>
        <Typography variant="caption">
          {r.statusName}
          {r.assigneeName ? ` · ${r.assigneeName}` : ""}
        </Typography>
        <Typography variant="caption">
          计划:{r.startDate ? fmtDateTime(r.startDate) : "?"} →{" "}
          {r.dueDate ? fmtDateTime(r.dueDate) : "?"}
        </Typography>
        {estH !== null && (
          <Typography variant="caption">预估工时:{fmtH(estH)}</Typography>
        )}
        {r.actualStart && (
          <Typography variant="caption">
            实际:{fmtDateTime(r.actualStart)} →{" "}
            {r.actualEnd && r.statusType === "FINAL"
              ? fmtDateTime(r.actualEnd)
              : "进行中"}
            {actH !== null ? ` · 工时 ${fmtH(actH)}` : ""}
          </Typography>
        )}
        {r.actualEnd && r.statusType !== "FINAL" && (
          <Typography variant="caption">
            上次完成:{fmtDateTime(r.actualEnd)}(返工中,SRS §6.6)
          </Typography>
        )}
        {r.anchorDate && (
          <Typography variant="caption">
            锚点:{fmtDateTime(r.anchorDate)}(依赖落位)
          </Typography>
        )}
        {r.checklistTotal ? (
          <Typography variant="caption">
            清单:{r.checklistDone ?? 0}/{r.checklistTotal}
          </Typography>
        ) : null}
      </Stack>
    );
  }

  function toggle(itemId: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  }

  // 折叠语义:折叠某个父,隐藏其全部后代
  function hiddenByCollapse(r: GanttRow): boolean {
    let cursor = r.parentItemId;
    const byId = new Map((rows ?? []).map((x) => [x.itemId, x]));
    while (cursor) {
      if (collapsed.has(cursor)) return true;
      cursor = byId.get(cursor)?.parentItemId ?? null;
    }
    return false;
  }

  const visible = (rows ?? []).filter((r) => !hiddenByCollapse(r));
  // 有子孙的父归入已排期(靠汇总条画);锚点行(YPJ-244:依赖落位画点)与进行中行(actualStart 有,画计划点/实际条)也归已排期;真未排期 = 无日期无子无锚无 actual
  const hasKid = (r: GanttRow) =>
    (rows ?? []).some((x) => x.parentItemId === r.itemId);
  // 五:case1/2 借 group 开始锚也归已排期;真未排期 = 全链无锚无 actual 叶行
  // SRS v4 §5.4(评审 P0-2 修订):计划层与实际层均无任何可渲染元素才入未排期组(actualEnd 也要查——直建 FINAL 完成点须在主面板渲染)
  const unscheduled = visible.filter(
    (r) =>
      !r.startDate &&
      !r.dueDate &&
      !hasKid(r) &&
      !r.anchorDate &&
      !r.actualStart &&
      !r.actualEnd &&
      !groupStartOf(r),
  );
  const scheduled = visible.filter(
    (r) =>
      r.startDate ||
      r.dueDate ||
      hasKid(r) ||
      r.anchorDate ||
      r.actualStart ||
      groupStartOf(r),
  );

  // YPJ-245(SRS §10.6):FINAL 行摘入「已完成(N)」组;父行仅当整子树 FINAL 才摘(防孤儿断树)
  function isCompletedRow(r: GanttRow): boolean {
    if (r.statusType !== "FINAL") return false;
    return (rows ?? [])
      .filter((x) => x.parentItemId === r.itemId)
      .every((x) => isCompletedRow(x));
  }
  const completedRows = scheduled.filter((r) => isCompletedRow(r));
  const mainRows = scheduled.filter((r) => !isCompletedRow(r));

  // YPJ-245(SRS §10.6):行渲染器抽出——主树与「已完成(N)」组复用同一渲染(双面板同步)
  const renderTitleCell = (r: GanttRow) => (
    <Box
      key={r.itemId}
      sx={{
        borderBottom: "1px solid",
        borderColor: "divider",
        minHeight: 44,
        display: "flex",
        alignItems: "center",
        "&:hover": { bgcolor: "action.hover" },
      }}
    >
      <Stack
        direction="row"
        spacing={0.5}
        alignItems="center"
        sx={{ pl: 1 + r.depth * 2, py: 0.5 }}
      >
        {hasKid(r) && (
          <Button
            size="small"
            onClick={() => toggle(r.itemId)}
            sx={{ minWidth: 0, px: 0.5 }}
          >
            {collapsed.has(r.itemId) ? "▶" : "▼"}
          </Button>
        )}
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题可点进详情;自然宽不截断,列内横滚(YPJ-236) */}
        <Link
          href={`/p/${key}/i/${r.itemId}?from=gantt`}
          style={{ minWidth: 0 }}
        >
          {/* component=div:inline span 吃不到盒模型属性,nowrap 文字会画出盒叠压 chip(YPJ-230) */}
          <Typography
            component="div"
            data-testid={`gantt-title-${r.number}`}
            variant="caption"
            noWrap
            sx={{
              color: "text.primary",
              textDecoration: "underline",
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
        {r.estimateValue !== null && (
          <Chip
            data-testid={`gantt-est-${r.number}`}
            label={`${r.estimateValue}${EST_UNIT_SHORT[r.estimateUnit ?? ""] ?? r.estimateUnit ?? ""}`}
            size="small"
            variant="outlined"
            sx={{ height: 18 }}
          />
        )}
        {r.blocked && (
          <Chip
            data-testid={`gantt-blocked-${r.number}`}
            label="🔗 blocked"
            size="small"
            color="warning"
            sx={{ height: 18 }}
          />
        )}
      </Stack>
    </Box>
  );

  const renderGanttRow = (r: GanttRow) => {
    const isFinal = r.statusType === "FINAL";
    const summary = hasKid(r) ? summaryOf(r.itemId) : null;
    const summaryLeft = summary ? (xOf(summary[0]) ?? 0) : 0;
    const summaryWidth =
      summary !== null
        ? Math.max((xEndOf(summary[1]) ?? 0) - summaryLeft, 1.2)
        : 0;
    // 有子孙的父行只画汇总条,不画自有条
    const drawOwn = !hasKid(r);
    // YPJ-244(spec 0151 五):渲染规则 v2——case 2×2 分型 × 3 状态(用户 Obsidian 2026-11-20)
    const wsm = wd?.[0] ?? 0;
    const wem = wd?.[1] ?? 1440;
    const estH = estimateHoursOf(r.estimateValue, r.estimateUnit, wsm, wem);
    const startAnchor = r.startDate ?? r.anchorDate ?? null;
    const gS = groupStartOf(r);
    const caseNo =
      !estH && startAnchor === null
        ? 1
        : estH && startAnchor === null
          ? 2
          : !estH
            ? 3
            : 4;
    // SRS §6.6(YPJ-245):FINAL 后返工=当前状态优先——按 §6.2 进行中渲染(黄条延伸今天,零徽标)
    const rework = r.actualEnd !== null && r.statusType !== "FINAL";
    const doing = r.actualStart !== null && (r.actualEnd === null || rework);
    const dueOnly = startAnchor === null && r.dueDate !== null; // 里程碑菱形(既有,不进矩阵)
    // 上条锚:case3/4=start/依赖锚;case2=actualStart(借实际锚)或 groupStart(未开始借 group 锚);case1=仅未开始在 group 开始画点
    const planT =
      startAnchor !== null
        ? +parseLocal(startAnchor)
        : dueOnly
          ? +parseLocal(r.dueDate!)
          : estH !== null
            ? r.actualStart
              ? +parseLocal(r.actualStart)
              : gS
                ? +parseLocal(gS)
                : null
            : !r.actualStart && gS
              ? +parseLocal(gS)
              : null;
    // 条 vs 点:有长度来源(due 显式或 estimate 铺算)→ 条;仅锚无长度 → 点
    const planIsBar =
      planT !== null && !dueOnly && (r.dueDate !== null || estH !== null);
    const planEndT =
      planT === null || !planIsBar
        ? null
        : r.dueDate !== null
          ? +parseLocal(r.dueDate) +
            (/^\d{4}-\d{2}-\d{2}$/.test(r.dueDate) ? DAY : 0)
          : dueFromJs(planT, estH!, wsm, wem);
    const left = planT === null ? 0 : xOfT(planT);
    const right = planEndT === null ? left : xOfT(planEndT);
    // 零时长条最小 3px 可见(YPJ-229;due-only 菱形是标记不受影响)
    const width = planT !== null ? Math.max(right - left, 3) : 0;
    // YPJ-222(spec 0147):实际条(叶+有迁出史;due-only 行不画)+ 清单遮罩 + 偏移徽标
    // YPJ-244(spec 0151)H5:实际层与计划 start 解耦——actualStart 有即画
    const act = drawOwn && r.actualStart !== null;
    const actLeft = act ? (xOf(r.actualStart) ?? 0) : 0;
    const actRight = act
      ? Math.max(
          isFinal && r.actualEnd
            ? (xEndOf(r.actualEnd) ?? actLeft)
            : Math.max(axis.todayX, actLeft),
          actLeft,
        )
      : 0;
    const actWidth = act ? Math.max(actRight - actLeft, 3) : 0;
    const actColor = isFinal ? "success.main" : "warning.main"; // 五:颜色3 绿=完成实际 / 颜色2 黄=进行中实际(返工行黄,SRS §6.6)
    const actPct =
      r.checklistTotal && r.checklistDone !== null
        ? Math.min(Math.round((r.checklistDone / r.checklistTotal) * 100), 100)
        : null;
    // YPJ-244(五)黄覆盖:进行中在计划条上叠「已流逝」段(末端=min(今天, 条尾));
    // 覆盖条件=计划条存在且有 estimate(due 显式无 est 不覆盖,同用户 case3 字面);
    // 周六日照算现状下日历时间位置等价工时比例;双休日历(H8)落地时改 hoursBetween 比例换算
    // SRS v4 §6.2(2026-11-21 拍板:进行中单条覆盖模型):凡有计划条的进行中行都画
    // 黄覆盖(条内已流逝段,start→min(今天,条尾),方案 A 口径);无第二条实际条
    const fillW =
      doing && planIsBar
        ? Math.min(Math.max(Math.min(axis.todayX, right) - left, 0), width)
        : 0;
    // YPJ-244(spec 0151)H5:直建 FINAL(actualComplete 有而 actualStart 无)→ 完成点
    const completeDot =
      drawOwn && isFinal && r.actualStart === null && r.actualEnd !== null;
    const badge = offsetBadge(r);
    const badgeLeft =
      Math.max(planT !== null ? right : left + 12, act ? actRight : 0) + 6;
    return (
      <Box
        key={r.itemId}
        sx={{
          position: "relative",
          borderBottom: "1px solid",
          borderColor: "divider",
          minHeight: 44,
          display: "flex",
          alignItems: "center",
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        {/* 条区(固定像素宽;高度 flex:1 随行 minHeight 44,自身不撑行——行对齐,SRS §10.2) */}
        <Box
          sx={{
            position: "relative",
            width: axis.timelineW,
            flexShrink: 0,
            flex: 1,
          }}
        >
          {axis.todayX >= 0 && axis.todayX <= axis.timelineW && (
            <Box
              sx={{
                position: "absolute",
                left: axis.todayX,
                top: 0,
                bottom: 0,
                width: "2px",
                bgcolor: "info.main",
                opacity: 0.5,
              }}
            />
          )}
          <Box
            data-summary={summary ? "" : undefined}
            sx={{
              position: "absolute",
              left: summaryLeft,
              width: summaryWidth,
              top: 4,
              display: summary ? "block" : "none",
            }}
          >
            <Box
              sx={{
                height: 8,
                borderRadius: 1,
                bgcolor: "warning.main", // YPJ-222:配色重整——汇总条灰→橙(spec 0147 Q3)
                opacity: 0.75,
              }}
            />
          </Box>
          {/* 折叠聚合徽标(YPJ-222) */}
          {summary && collapsed.has(r.itemId) && (
            <Chip
              label={`+${descendantsOf(r.itemId)} 子任务`}
              size="small"
              data-testid={`gantt-collapsed-${r.number}`}
              sx={{
                position: "absolute",
                left: summaryLeft + summaryWidth + 4,
                top: 0,
                height: 16,
                fontSize: 10,
              }}
            />
          )}
          <Box
            data-final={isFinal ? "" : undefined}
            data-testid={`gantt-bar-${r.number}`}
            sx={{
              position: "absolute",
              left,
              width,
              top: 5,
              display: drawOwn && planT !== null ? "block" : "none",
            }}
          >
            {planIsBar ? (
              <Box
                sx={{
                  position: "relative",
                  height: 12,
                  borderRadius: 1,
                  bgcolor: r.overdue ? "error.main" : "primary.main",
                  opacity: 0.85,
                }}
              >
                {fillW > 0 && (
                  <Box
                    data-testid={`gantt-fill-${r.number}`}
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
                )}
              </Box>
            ) : dueOnly ? (
              <Box
                sx={{
                  width: 12,
                  height: 12,
                  transform: "rotate(45deg)",
                  bgcolor: r.overdue ? "error.main" : "warning.main",
                  opacity: 1,
                  mx: "auto",
                }}
              />
            ) : (
              // spec 0151 五:case1/3 计划层圆点——位置=group 开始(case1 未开始)/ start 或依赖锚(case3)
              <Box
                data-testid={`gantt-${caseNo === 1 ? "grouppoint" : r.anchorDate && r.startDate === null ? "anchor" : "plandot"}-${r.number}`}
                sx={{
                  width: 3,
                  height: 12,
                  borderRadius: 1,
                  bgcolor: "primary.main",
                }}
              />
            )}
          </Box>
          {/* 实际条(下条):仅 无计划条的进行中(case1/3b 黄条)/ 完成(绿条)/ 返工 渲染;
                            有计划条的进行中行 = 单条覆盖模型,不再画第二条(SRS v4 §6.2,2026-11-21 用户拍板) */}
          {act && !(doing && planIsBar) && (
            <Box
              data-testid={`gantt-actual-${r.number}`}
              sx={{
                position: "absolute",
                left: actLeft,
                width: actWidth,
                top: 24,
              }}
            >
              <Box
                sx={{
                  position: "relative",
                  height: 12,
                  borderRadius: 1,
                  bgcolor: actColor,
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
              data-testid={`gantt-complete-${r.number}`}
              sx={{
                position: "absolute",
                left: (xEndOf(r.actualEnd) ?? 0) - 5,
                top: 26,
                width: 10,
                height: 10,
                borderRadius: "50%",
                bgcolor: isFinal ? "text.disabled" : "success.main",
                opacity: isFinal ? 0.5 : 0.85,
              }}
            />
          )}
          {/* 偏移徽标:仅 FINAL;On time 中性 / ±Nh / ±Nd,提前绿/延期红(SRS §11.1,YPJ-245) */}
          {badge !== null && (
            <Typography
              variant="caption"
              data-testid={`gantt-offset-${r.number}`}
              sx={{
                position: "absolute",
                left: badgeLeft,
                top: 24,
                lineHeight: "12px",
                fontWeight: 600,
                color: badge.color,
                whiteSpace: "nowrap",
              }}
            >
              {badge.label}
            </Typography>
          )}
          <Link
            href={`/p/${key}/i/${r.itemId}?from=gantt`}
            style={{
              position: "absolute",
              left: 0,
              top: 0,
              right: 0,
              bottom: 0,
              display: "block",
            }}
            aria-label={r.number}
          >
            {/* hover 详情卡命中区 = 条/点矩形(YPJ-222;其余区域仍整块直进详情) */}
            {drawOwn &&
              (planT !== null ||
                (r.actualStart !== null &&
                  (r.actualEnd === null || rework))) && (
                <Tooltip title={hoverCard(r)}>
                  <Box
                    sx={{
                      position: "absolute",
                      left,
                      top: 5,
                      width: Math.max(width, 12),
                      height: 12,
                    }}
                  />
                </Tooltip>
              )}
            {act && (
              <Tooltip title={hoverCard(r)}>
                <Box
                  sx={{
                    position: "absolute",
                    left: actLeft,
                    top: 24,
                    width: actWidth,
                    height: 12,
                  }}
                />
              </Tooltip>
            )}
            {summary && (
              <Tooltip title={hoverCard(r)}>
                <Box
                  sx={{
                    position: "absolute",
                    left: summaryLeft,
                    top: 4,
                    width: summaryWidth,
                    height: 8,
                  }}
                />
              </Tooltip>
            )}
          </Link>
        </Box>

        <Chip
          label={r.statusName}
          size="small"
          variant="outlined"
          sx={{ mr: 1, height: 20, position: "sticky", right: 0 }}
        />
      </Box>
    );
  };

  return (
    <PageShell>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{`${String(key)} · 甘特`}</Typography>
        <Stack
          direction="row"
          spacing={0.5}
          sx={{ ml: "auto" }}
          alignItems="center"
        >
          {/* 范围选择器(YPJ-220):五选 + 二级对象选择 */}
          <Select
            size="small"
            data-testid="gantt-scope"
            value={scope}
            onChange={(e) => changeScope(e.target.value)}
            sx={{ minWidth: 96 }}
            aria-label="范围"
          >
            <MenuItem value="board">看板</MenuItem>
            <MenuItem value="group">分组</MenuItem>
            <MenuItem value="milestone">里程碑</MenuItem>
            <MenuItem value="release">发布</MenuItem>
            <MenuItem value="all">全部</MenuItem>
          </Select>
          {scope === "group" && (
            <Autocomplete
              multiple
              disableCloseOnSelect // 多选:选完一项弹层保持,继续选下一项
              size="small"
              data-testid="gantt-scope-groups"
              options={groupOpts}
              getOptionLabel={(o) => o.name}
              isOptionEqualToValue={(a, b) => a.groupId === b.groupId}
              value={groupOpts.filter((g) => scopeIds.includes(g.groupId))}
              onChange={(_, v) => setScopeIds(v.map((g) => g.groupId))}
              renderInput={(params) => (
                <TextField
                  {...params}
                  placeholder="选择分组"
                  sx={{ minWidth: 180 }}
                />
              )}
            />
          )}
          {(scope === "milestone" || scope === "release") && (
            <Select
              size="small"
              displayEmpty
              data-testid={
                scope === "milestone"
                  ? "gantt-scope-milestone"
                  : "gantt-scope-release"
              }
              value={scopeIds[0] ?? ""}
              onChange={(e) =>
                setScopeIds(e.target.value ? [e.target.value] : [])
              }
              sx={{ minWidth: 160 }}
              aria-label="范围对象"
            >
              <MenuItem value="" disabled>
                {scope === "milestone" ? "选择里程碑" : "选择发布"}
              </MenuItem>
              {(scope === "milestone"
                ? msOpts.map((o) => ({ id: o.milestoneId, name: o.name }))
                : relOpts.map((o) => ({ id: o.releaseId, name: o.name }))
              ).map((o) => (
                <MenuItem key={o.id} value={o.id}>
                  {o.name}
                </MenuItem>
              ))}
            </Select>
          )}
          {/* 缩放控件(v2 连续系数):− / + ;Ctrl+滚轮亦可(锚点=鼠标) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-zoom-out"
            aria-label="缩小"
            onClick={() => zoomTo(axis.pxPerDay / ZOOM_STEP)}
          >
            −
          </Button>
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-zoom-in"
            aria-label="放大"
            onClick={() => zoomTo(axis.pxPerDay * ZOOM_STEP)}
          >
            +
          </Button>
          {/* 全览:回到缺省视窗(fit+下限;宽区间下不再压满一屏,YPJ-234) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-fit"
            onClick={() => {
              setZoom(null);
              setCenter("");
            }}
          >
            全览
          </Button>
          {/* 今日回中(YPJ-234 案乙:只居中不重置缩放;回缺省缩放用「全览」) */}
          <Button
            size="small"
            variant="outlined"
            data-testid="gantt-today"
            onClick={centerOnToday}
          >
            今日
          </Button>
          {/* 日窗预设(视点日为中心,N 天撑满) */}
          {[1, 2, 3, 5].map((n) => (
            <Button
              key={n}
              size="small"
              variant="outlined"
              data-testid={`gantt-win-${n}`}
              onClick={() => goToDayWindow(n)}
            >
              {n}日
            </Button>
          ))}
          {/* 视点日导航:◀ 前一天 / 后一天 ▶ / 指定日期 */}
          <Stack
            direction="row"
            spacing={0.5}
            alignItems="center"
            data-testid="gantt-center-nav"
          >
            <Button
              size="small"
              variant="outlined"
              data-testid="gantt-center-prev"
              onClick={() => shiftCenter(-1)}
            >
              ◀
            </Button>
            <DateInput
              ariaLabel="视点日"
              size="small"
              value={center}
              onChange={(v) => setCenter(v)}
              sx={{ width: 160 }}
            />
            <Button
              size="small"
              variant="outlined"
              data-testid="gantt-center-next"
              onClick={() => shiftCenter(1)}
            >
              ▶
            </Button>
          </Stack>
        </Stack>
      </Stack>

      {rows === null ? (
        <Typography color="text.secondary">加载中…</Typography>
      ) : rows.length === 0 ? (
        <Typography color="text.secondary">{t.projects.empty}</Typography>
      ) : (
        <>
          {/* 未排期折叠组 */}
          {unscheduled.length > 0 && (
            <Box sx={{ mb: 2 }}>
              <Button
                size="small"
                variant="outlined"
                onClick={() => setShowUnscheduled((v) => !v)}
              >
                {showUnscheduled ? "▼" : "▶"} 未排期({unscheduled.length})
              </Button>
              {showUnscheduled &&
                unscheduled.map((r) => (
                  <Box key={r.itemId} sx={{ pl: 2 + r.depth * 2, mt: 0.5 }}>
                    <RowTitle r={r} />
                  </Box>
                ))}
            </Box>
          )}

          {/* 双面板(YPJ-236):标题列独立横滚+右边线+异色底;甘特区缩放横滚;纵向行对齐 */}
          <Stack
            direction="row"
            sx={{
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 1,
              overflow: "hidden",
              alignItems: "stretch",
            }}
          >
            {/* 标题面板:标题按自然宽呈现,超宽时列内底部横滚(YPJ-236) */}
            <Box
              data-testid="gantt-titlepane"
              sx={{
                width: titleW,
                flexShrink: 0,
                overflowX: "auto",
                overflowY: "hidden",
                bgcolor: "grey.50",
                borderRight: "1px solid",
                borderColor: "divider",
              }}
            >
              <Box sx={{ width: "max-content", minWidth: "100%" }}>
                {/* 表头占位:与甘特区刻度行同高同边框,保证行对齐 */}
                {axis.headerRows.map((row) => (
                  <Box
                    key={row.gran}
                    sx={{
                      height: 24,
                      borderBottom: "1px solid",
                      borderColor: "divider",
                    }}
                  />
                ))}
                {mainRows.map(renderTitleCell)}
                {completedRows.length > 0 && (
                  <Box
                    sx={{
                      borderBottom: "1px solid",
                      borderColor: "divider",
                      minHeight: 44,
                      display: "flex",
                      alignItems: "center",
                    }}
                  >
                    <Button
                      size="small"
                      data-testid="gantt-completed-group"
                      onClick={() => setShowCompleted((v) => !v)}
                      sx={{ pl: 1 }}
                    >
                      {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
                    </Button>
                  </Box>
                )}
                {showCompleted && completedRows.map((r) => renderTitleCell(r))}
              </Box>
            </Box>
            {/* 237 分割线:骑在标题列右边线上的透明热区,hover/拖拽/键盘焦点显 info.main 竖线 */}
            <Box
              data-testid="gantt-splitter"
              role="separator"
              aria-orientation="vertical"
              aria-label="调整标题列宽度"
              tabIndex={0}
              onMouseDown={onSplitMouseDown}
              onDoubleClick={() => persistTitleW(TITLE_W_DEFAULT)}
              onKeyDown={(e) => {
                if (e.key === "ArrowLeft") {
                  e.preventDefault();
                  persistTitleW(titleW - 16);
                } else if (e.key === "ArrowRight") {
                  e.preventDefault();
                  persistTitleW(titleW + 16);
                }
              }}
              sx={{
                width: 9,
                flexShrink: 0,
                ml: "-5px", // 骑上右边线;不用 sx 数字(被 theme spacing 放大,实锤 -35px)
                position: "relative",
                zIndex: 1,
                cursor: "col-resize",
                "&::after": {
                  content: '""',
                  position: "absolute",
                  top: 0,
                  bottom: 0,
                  left: 3.5,
                  width: 2,
                  bgcolor: splitting ? "info.main" : "transparent",
                },
                "&:hover::after, &:focus-visible::after": {
                  bgcolor: "info.main",
                },
              }}
            />
            {/* 甘特面板:缩放/横滚;今日线、条、状态章 */}
            <Box
              ref={scrollRef}
              data-testid="gantt-scroll"
              sx={{
                position: "relative",
                flex: 1,
                minWidth: 0,
                overflowX: "auto",
                overflowY: "hidden",
              }}
            >
              <Box sx={{ width: axis.timelineW, minWidth: "100%" }}>
                {/* header 自适应 2–3 行(月/周/日/时,自上而下粗→细) */}
                {axis.headerRows.map((row) => (
                  <Box
                    key={row.gran}
                    data-testid={`gantt-row-${row.gran}`}
                    sx={{
                      position: "relative",
                      height: 24,
                      borderBottom: "1px solid",
                      borderColor: "divider",
                      bgcolor: "background.default",
                    }}
                  >
                    {row.ticks.map((tk) => (
                      <Typography
                        key={tk.key}
                        variant="caption"
                        color="text.secondary"
                        sx={{
                          position: "absolute",
                          left: tk.x + 4,
                          top: 2,
                          whiteSpace: "nowrap",
                        }}
                      >
                        {tk.label}
                      </Typography>
                    ))}
                  </Box>
                ))}

                {scheduled.length === 0 && (
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ p: 2 }}
                  >
                    暂无已排期 item——在详情页填起止日期后出现在这里。
                  </Typography>
                )}

                {mainRows.map(renderGanttRow)}
                {completedRows.length > 0 && (
                  <Box
                    sx={{
                      minHeight: 44,
                      borderBottom: "1px solid",
                      borderColor: "divider",
                      display: "flex",
                      alignItems: "center",
                    }}
                  >
                    <Button
                      size="small"
                      data-testid="gantt-completed-group-bar"
                      onClick={() => setShowCompleted((v) => !v)}
                      sx={{ ml: 1 }}
                    >
                      {showCompleted ? "▼" : "▶"} 已完成({completedRows.length})
                    </Button>
                  </Box>
                )}
                {showCompleted && completedRows.map((r) => renderGanttRow(r))}
              </Box>
            </Box>
          </Stack>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ mt: 1, display: "block" }}
          >
            蓝竖线 = 今天 · 菱形 = 里程碑(只有截止日期) · 红色 = 超期 · 橙横条 =
            父汇总(子孙并集,折叠显 +N 子任务) · 列 = 24h 钟表轴(条长 ∝ 钟表跨度)
            · 上条 = 计划(蓝) / 下条 = 实际 (绿,深色 = 清单完成率) · 绿填充 =
            已流逝(进行中,今天 vs due,用实际 色覆盖计划) · 蓝点 =
            依赖锚点落位(无 start 无 due) · On time/±Nh/±Nd =
            偏移徽标(仅完成行,提前绿/延期红) · 悬停条看详情 · Ctrl+滚轮或 ± 缩放
            · 「今日」回中今天
          </Typography>
        </>
      )}
    </PageShell>
  );

  function RowTitle({ r }: { r: GanttRow }) {
    return (
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip
          label={r.number}
          size="small"
          variant="outlined"
          sx={{ height: 20 }}
        />
        {/* 标题列可点进详情(条形区另有整块隐形覆盖链接,YPJ-220 反馈补) */}
        <Link href={`/p/${key}/i/${r.itemId}?from=gantt`}>
          <Typography
            variant="caption"
            color="text.primary"
            noWrap
            sx={{
              textDecoration: "underline",
              "&:hover": { color: "primary.main" },
            }}
          >
            {r.title}
          </Typography>
        </Link>
      </Stack>
    );
  }
}
