"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { listSprints } from "@/lib/sprints";
import { EstimateScale } from "@/lib/estimate";
import type { Item, SprintLite, Status } from "../types";

/** 看板数据球:project+board+sprints 三拉;30s 自动刷新(V9-Q3)+ `yz:project-changed` 广播重拉(YPJ-13)+ last-project 记忆(V11-S1)。setItems 外露供 moveTo 乐观更新。 */
export default function useBoardData(key: string) {
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [activeSprints, setActiveSprints] = useState<SprintLite[]>([]);
  const [sprints, setSprints] = useState<SprintLite[]>([]);
  const [estScales, setEstScales] = useState<EstimateScale[]>([]);
  const [estRequired, setEstRequired] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // V11-S1:记录最后访问的项目
  useEffect(() => {
    const token = localStorage.getItem("yz-token");
    if (token && key) {
      fetch(`/api/members/me/last-project`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ key: String(key) }),
      }).catch(() => {});
    }
  }, [key]);

  const load = useCallback(async () => {
    try {
      const project = await api<{
        statuses: Status[];
        estimateScales: EstimateScale[];
        estimateRequired: boolean;
      }>(`/api/projects/${key}`);
      setStatuses(project.statuses);
      setEstScales(project.estimateScales ?? []);
      setEstRequired(project.estimateRequired ?? false);
      // V9-S1:item 列表自带 feasSignal 冗余,不再单 feasibility 调用
      // YPJ-29:看板数据范围由后端决定(active sprint 的 items;无 active → 全量)
      const board = await api<{ activeGroups: SprintLite[]; items: Item[] }>(
        `/api/projects/${key}/board`,
      );
      setActiveSprints(board.activeGroups ?? []);
      setItems(board.items);
      setSprints(await listSprints(key));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  // 项目级面板(工作流/成员)改动 → 刷新本页数据(面板已上移至 layout,YPJ-13)
  useEffect(() => {
    const h = () => load();
    window.addEventListener("yz:project-changed", h);
    return () => window.removeEventListener("yz:project-changed", h);
  }, [load]);

  // V9-Q3:看板 30s 自动刷新(手刷按钮同函数)
  useEffect(() => {
    const t = setInterval(() => load(), 30000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 与原 page 一致:挂载一次,load 闭包随 key 稳定
  }, []);

  return {
    statuses,
    items,
    setItems,
    activeSprints,
    sprints,
    estScales,
    estRequired,
    loading,
    error,
    setError,
    load,
  };
}
