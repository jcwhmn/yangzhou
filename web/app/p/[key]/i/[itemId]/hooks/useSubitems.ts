// YPJ-257 页面重构(纯结构):子条目域状态与动作自 page 下沉
// schedule/clearSchedule:YPJ-218(spec 0139)迁移定案②——父自有时间线承接给新子后清父时间线
import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

import type { Item } from "../types";

export function useSubitems(
  itemId: string,
  key: string,
  itemTitle: string,
  schedule: { startDate: string; dueDate: string },
  clearSchedule: () => void,
  setError: (msg: string) => void,
  reloadItem: () => Promise<void>,
) {
  const [subitems, setSubitems] = useState<Item[] | null>(null);

  const [newSubTitle, setNewSubTitle] = useState("");

  const [subPolicyDialog, setSubPolicyDialog] = useState(false); // YPJ-218 spec 0139:加子时父自有时间线策略

  // 已有子的父不持有自有时间线:时间线框禁用(spec 0139 定案)
  const timelineLocked = (subitems ?? []).length > 0;

  // YPJ-84:子条目 = 直接 children,复用全量列表客户端过滤(不加后端端点,spec 0071)
  const loadSubitems = useCallback(async () => {
    if (!itemId) return;

    try {
      const list = await api<Item[]>(`/api/projects/${key}/items`);

      setSubitems(list.filter((i) => i.parentItemId === itemId));
    } catch {
      // 同依赖区:页签内列表,加载失败静默
    }
  }, [key, itemId]);

  useEffect(() => {
    loadSubitems();
  }, [loadSubitems]);

  const createSubitem = useCallback(
    async (title: string) => {
      try {
        await api(`/api/projects/${key}/items`, {
          method: "POST",

          body: JSON.stringify({
            title,
            parentItemId: itemId,
          }),
        });

        setNewSubTitle("");

        await loadSubitems();
      } catch (e) {
        setError(e instanceof Error ? e.message : "创建失败");
      }
    },
    [itemId, key, loadSubitems, setError],
  );

  const addSubitem = useCallback(async () => {
    if (!newSubTitle.trim()) return;

    // YPJ-218(spec 0139):父无子且自有时间线时,加子先弹策略选择(迁移默认);有子时时间线框已禁用,直接建
    if (
      (subitems ?? []).length === 0 &&
      (schedule.startDate || schedule.dueDate)
    ) {
      setSubPolicyDialog(true);
      return;
    }
    await createSubitem(newSubTitle.trim());
  }, [
    createSubitem,
    newSubTitle,
    schedule.startDate,
    schedule.dueDate,
    subitems,
  ]);

  // 定案②迁移:子「{父标题}·原排期」承接父原时间线(POST 后 PATCH 补日期),父清空后再建新子
  const addSubitemWithPolicy = useCallback(
    async (policy: "ignore" | "migrate") => {
      setSubPolicyDialog(false);
      const title = newSubTitle.trim();
      if (!title) return;

      try {
        if (policy === "migrate") {
          const legacy = (await api<{ itemId: string }>(
            `/api/projects/${key}/items`,
            {
              method: "POST",
              body: JSON.stringify({
                title: `${itemTitle}·原排期`,
                parentItemId: itemId,
              }),
            },
          )) as { itemId: string };
          await api(`/api/items/${legacy.itemId}`, {
            method: "PATCH",
            body: JSON.stringify({
              startDate: schedule.startDate,
              dueDate: schedule.dueDate,
            }),
          });
        }
        await api(`/api/items/${itemId}`, {
          method: "PATCH",
          body: JSON.stringify({ startDate: "", dueDate: "" }),
        });
        clearSchedule();
        await createSubitem(title);
        await reloadItem();
      } catch (e) {
        setError(e instanceof Error ? e.message : "创建失败");
      }
    },
    [
      clearSchedule,
      createSubitem,
      itemTitle,
      itemId,
      key,
      newSubTitle,
      reloadItem,
      schedule.dueDate,
      schedule.startDate,
      setError,
    ],
  );

  return {
    subitems,
    newSubTitle,
    setNewSubTitle,
    subPolicyDialog,
    setSubPolicyDialog,
    timelineLocked,
    loadSubitems,
    addSubitem,
    addSubitemWithPolicy,
  };
}
