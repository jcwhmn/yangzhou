"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "@/lib/api";
import {
  type GanttRow,
  type MilestoneMarker,
  type ReleaseMarker,
} from "../types";
import { hmToMin } from "../utils/timeCalculations";

/** 甘特数据获取与借锚(YPJ-249 自 page.tsx 原样搬出,零语义变化)。
 * as-built 偏差(spec 0153 §状态拍板):rows/wd/二级选项的 useState 随 fetch 收进 hook(load/序号守卫/两个 effect 是其专属机制),入口经返回值持有;scope 状态仍留入口。 */
export function useGanttData(key: string, scope: string, scopeIds: string[]) {
  const [rows, setRows] = useState<GanttRow[] | null>(null);
  const [wd, setWd] = useState<[number, number, boolean] | null>(null); // 五:工作日历窗(分钟;默认 09:00-18:00)+ 工作制跳周末(YPJ-226 spec 0163)
  const loadSeqRef = useRef(0); // 响应序号守卫:切范围后慢响应晚到不覆盖新状态

  // 范围二级选项(group/milestone/release;列表 API 均返回裸数组)。ms/rel 取全 DTO(YPJ-221 竖线旗标复用同一响应)
  const [groupOpts, setGroupOpts] = useState<
    { groupId: string; name: string }[]
  >([]);
  const [msOpts, setMsOpts] = useState<MilestoneMarker[]>([]);
  const [relOpts, setRelOpts] = useState<ReleaseMarker[]>([]);

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
      api<{ workdayStart: string; workdayEnd: string; workMode: string }>(
        `/api/projects/${key}`,
      ),
    ]);
    if (seq !== loadSeqRef.current) return; // 已有更新的范围请求,丢弃过期响应
    setRows(data.rows);
    setWd([
      hmToMin(proj.workdayStart),
      hmToMin(proj.workdayEnd),
      proj.workMode === "work",
    ]);
  }, [key, scope, scopeIds]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  // 二级选项一次拉取(失败静默:选择器留空,不阻塞甘特)
  useEffect(() => {
    api<{ groupId: string; name: string }[]>(`/api/projects/${key}/groups`)
      .then(setGroupOpts)
      .catch(() => {});
    api<MilestoneMarker[]>(`/api/projects/${key}/milestones`)
      .then(setMsOpts)
      .catch(() => {});
    api<ReleaseMarker[]>(`/api/projects/${key}/releases`)
      .then(setRelOpts)
      .catch(() => {});
  }, [key]);

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

  return { rows, wd, groupOpts, msOpts, relOpts, groupStartOf };
}
