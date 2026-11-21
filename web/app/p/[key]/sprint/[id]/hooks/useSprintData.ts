"use client";

import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { getSprint, listGroups, listParentCandidates } from "@/lib/sprints";
import {
  type Item,
  type Sprint,
  type Status,
  type TypeWorkflow,
} from "../types";

/** useSprintData(YPJ-252 spec 0155 拍板 1:单屏单一数据球收数据层;动作编排与弹窗态留 page) */
export function useSprintData(projectKey: string, groupId: string) {
  const [sprint, setSprint] = useState<Sprint | null>(null);
  // 0110:版本号防乱序覆盖——初始加载的慢响应不得覆盖动作后的本地状态(单步弹出后点击更快,竞态实测)
  const sprintVer = useRef(0);
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [error, setError] = useState("");
  const [gone, setGone] = useState(false);
  // spec 0114:全量组——处置目标树/开始提示按本组类型取候选(L1-only 不再够用)
  const [groups, setGroups] = useState<Sprint[]>([]);
  // spec 0114:上一层组选项(null=顶层组/平面项目,不渲染选择器)
  const [parentSel, setParentSel] = useState<{
    name: string;
    options: { sprintId: string; name: string }[];
  } | null>(null);
  // spec 0114:level-1 类型 key(判定本组是否叶层;null=项目无组类型)
  const [l1Key, setL1Key] = useState<string | null>(null);
  // spec 0114:本组类型显示名(按钮/确认弹窗文案「开始{类型名}」动态化)
  const [typeName, setTypeName] = useState<string | null>(null);
  const [wf, setWf] = useState<TypeWorkflow[]>([]);

  useEffect(() => {
    const ver = ++sprintVer.current;
    Promise.all([
      getSprint(projectKey, groupId),
      api<Item[]>(`/api/projects/${projectKey}/groups/${groupId}/items`),
      api<{ statuses: Status[] }>(`/api/projects/${projectKey}`),
      listGroups(projectKey),
      api<{ typeKey: string; name: string; level: number | null }[]>(
        `/api/projects/${projectKey}/group-types`,
      ),
    ])
      .then(([s, its, p, gs, tps]) => {
        if (sprintVer.current === ver) setSprint(s);
        setSprint(s);
        setItems(its);
        setStatuses(p.statuses);
        setGroups(gs);
        const l1 = tps.find((tp) => tp.level === 1)?.typeKey ?? null;
        setL1Key(l1);
        setTypeName(tps.find((tp) => tp.typeKey === s.typeKey)?.name ?? null);
        // spec 0114:workflow 按本组自身类型拉取(类型纯用户配置,不得硬编码 sprint)
        api<TypeWorkflow[]>(
          `/api/projects/${projectKey}/group-types/${s.typeKey}/workflow`,
        )
          .then(setWf)
          .catch(() => setWf([]));
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setGone(true);
        else setError(e instanceof Error ? e.message : "加载失败");
      });
  }, [projectKey, groupId]);

  useEffect(() => {
    // spec 0114:上一层组候选按本组类型 L+1 动态拉取;无上层类型(顶层/平面)→ null
    if (!sprint) return;
    listParentCandidates(projectKey, sprint.typeKey)
      .then(setParentSel)
      .catch(() => setParentSel(null));
  }, [projectKey, sprint]);

  return {
    sprint,
    setSprint,
    sprintVer,
    items,
    setItems,
    statuses,
    groups,
    l1Key,
    typeName,
    wf,
    parentSel,
    error,
    setError,
    gone,
  };
}
