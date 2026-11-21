"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type RefObject,
} from "react";
import {
  TITLE_W_DEFAULT,
  TITLE_W_LS,
  TITLE_W_MIN,
  type GanttRow,
} from "../types";

/** 视口交互与工况持久化(YPJ-249 自 page.tsx 原样搬出,零语义变化)。
 * as-built 偏差(spec 0153 §状态拍板):containerW/titleW/splitting 的 useState 与 splitRef 等交互专属 refs 收进 hook;scrollRef 由入口持有并注入(挂在 JSX 上)。 */
export function useGanttInteraction(opts: {
  scrollRef: RefObject<HTMLDivElement | null>;
  rows: GanttRow[] | null;
  savedView: {
    zoom?: number;
    center?: string;
    scroll?: number;
    scope?: string;
    scopeIds?: string[];
  };
  zoom: number | null;
  center: string;
  scope: string;
  scopeIds: string[];
}) {
  const { scrollRef, rows, savedView, zoom, center, scope, scopeIds } = opts;
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

  return {
    containerW,
    titleW,
    splitting,
    onSplitMouseDown,
    persistTitleW,
    saveView,
  };
}
