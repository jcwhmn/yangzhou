// YPJ-257(spec 0168):桶内 consecutive 浏览——桶 = 看板(跨列连续:状态列序 × boardOrder)→ backlog(API 序)→ planned L1 组(API 序);
// spec 0098:board ∩ backlog = ∅,planned 组成员两处皆不在;多组命中取首个(createdAt 序,正常一票一 L1 组)
import { useEffect, useState } from "react";

import { useRouter } from "next/navigation";

import { api } from "@/lib/api";

import { t } from "@/lib/texts";

import type { Item, SprintLite, Status } from "../types";

import type { Filter } from "../../../types";

export function useItemNav(
  item: Item | null,
  itemId: string,
  key: string,
  sprints: SprintLite[],
  statuses: Status[],
) {
  const router = useRouter();

  const [nav, setNav] = useState<{
    prev: string | null;
    next: string | null;
    index: number;
    total: number;
  } | null>(null);

  // 首/尾边界轻提示(用户拍板 2026-11-25:无反应要有感知)
  const [navEdge, setNavEdge] = useState<string | null>(null);

  useEffect(() => {
    if (!item) return;
    let dead = false;
    (async () => {
      const byBoardOrder = (a: Item, b: Item) =>
        (a.boardOrder ?? Number.MAX_SAFE_INTEGER) -
          (b.boardOrder ?? Number.MAX_SAFE_INTEGER) ||
        a.number.localeCompare(b.number, undefined, { numeric: true });
      let list: Item[] | null = null;
      // 同看板 S7 assignee 过滤(单选 chip,localStorage 持久化,YPJ-257):导航只在过滤后的可见集内
      const f = localStorage.getItem(`yz.board.filter.${key}`) as Filter | null;
      const visible = (l: Item[]) =>
        !f || f === "all"
          ? l
          : f === "unassigned"
            ? l.filter((i) => !i.assignee)
            : f === "blocked"
              ? l.filter((i) => i.blocked)
              : l.filter((i) => i.assignee === f);
      const board = await api<{ items: Item[] }>(
        `/api/projects/${key}/board`,
      ).catch(() => null);
      const me = board?.items.find((i) => i.itemId === itemId);
      if (board && me) {
        // 用户拍板 2026-11-25:跨列连续浏览——状态按看板列序(workflow),列内 boardOrder 序,列尾接下一列首
        const rank = new Map(statuses.map((st, i) => [st.name, i]));
        list = visible(board.items).sort(
          (a, b) =>
            (rank.get(a.status) ?? Number.MAX_SAFE_INTEGER) -
              (rank.get(b.status) ?? Number.MAX_SAFE_INTEGER) ||
            byBoardOrder(a, b),
        );
      } else {
        const backlog = await api<Item[]>(`/api/projects/${key}/backlog`).catch(
          () => [],
        );
        if (visible(backlog).some((i) => i.itemId === itemId)) {
          // 同 backlog 页显示口径:API 返回序(number 字典序),不重排
          list = visible(backlog);
        } else {
          for (const g of sprints.filter((s) => s.status !== "completed")) {
            const members = await api<Item[]>(
              `/api/projects/${key}/groups/${g.sprintId}/items`,
            ).catch(() => []);
            if (visible(members).some((i) => i.itemId === itemId)) {
              // 同组页表格口径:API 返回序,不重排
              list = visible(members);
              break;
            }
          }
        }
      }
      if (dead) return;
      if (!list) {
        setNav(null);
        return;
      }
      const idx = list.findIndex((i) => i.itemId === itemId);
      setNav({
        prev: idx > 0 ? list[idx - 1].number : null,
        next: idx < list.length - 1 ? list[idx + 1].number : null,
        index: idx,
        total: list.length,
      });
    })();
    return () => {
      dead = true;
    };
  }, [item, itemId, key, sprints, statuses]);

  // Ctrl+←/→ 桶内导航;文本编辑焦点让位(spec 0168)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.ctrlKey || (e.key !== "ArrowLeft" && e.key !== "ArrowRight"))
        return;
      const el = e.target as HTMLElement | null;
      if (
        el &&
        (el.isContentEditable ||
          ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName))
      )
        return;
      if (!nav) return;
      const target = e.key === "ArrowLeft" ? nav.prev : nav.next;
      if (target) {
        e.preventDefault();
        router.push(`/p/${key}/i/${target}`);
      } else {
        e.preventDefault();
        setNavEdge(e.key === "ArrowLeft" ? t.item.navFirst : t.item.navLast);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [nav, key, router]);

  return { nav, navEdge, setNavEdge };
}
