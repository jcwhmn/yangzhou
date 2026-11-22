"use client";

import { parseLocal } from "@/lib/dates";
import { DAY, type GanttRow } from "../types";
import { dueFromJs, estimateHoursOf } from "../utils/timeCalculations";

/** 包络与分组(YPJ-249 自 page.tsx 原样搬出,零语义变化):父行包络=可见子孙叶行渲染元素时间并集(SRS §7.2),折叠语义/未排期判定/已完成组摘取(SRS §10.6)。
 * collapsed 状态留入口;hiddenByCollapse/isCompletedRow 仅内部消费不暴露。 */
export function useEnvelope(opts: {
  rows: GanttRow[] | null;
  wd: [number, number, boolean] | null;
  groupStartOf: (r: GanttRow) => string | null;
  collapsed: Set<string>;
}) {
  const { rows, wd, groupStartOf, collapsed } = opts;
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
          const estEnd =
            estH !== null
              ? dueFromJs(a, estH, wsm, wem, wd?.[2] ?? false)
              : null;
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

  return {
    summaryOf,
    descendantsOf,
    visible,
    hasKid,
    unscheduled,
    scheduled,
    completedRows,
    mainRows,
  };
}
