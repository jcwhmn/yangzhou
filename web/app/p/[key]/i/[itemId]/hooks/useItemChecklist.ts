// YPJ-257 页面重构(纯结构):清单域状态与动作自 page 下沉
import { useCallback, useState } from "react";

import { api } from "@/lib/api";

import type { Checklist } from "../types";

export function useItemChecklist(
  itemId: string,
  setError: (msg: string) => void,
) {
  const [checklist, setChecklist] = useState<Checklist | null>(null);

  const [newCheckText, setNewCheckText] = useState("");

  const reloadChecklist = useCallback(async () => {
    setChecklist(
      await api<Checklist>(`/api/items/${itemId}/checklist`).catch(() => null),
    );
  }, [itemId]);

  const addCheckEntry = useCallback(async () => {
    if (!newCheckText.trim()) return;

    setError("");

    try {
      await api(`/api/items/${itemId}/checklist`, {
        method: "POST",

        body: JSON.stringify({ text: newCheckText.trim() }),
      });

      setNewCheckText("");

      await reloadChecklist();
    } catch (e) {
      setError(e instanceof Error ? e.message : "添加失败");
    }
  }, [itemId, newCheckText, reloadChecklist, setError]);

  const toggleCheckEntry = useCallback(
    async (entryId: string, done: boolean) => {
      await api(`/api/checklist/${entryId}`, {
        method: "PATCH",
        body: JSON.stringify({ done }),
      }).catch(() => {});

      await reloadChecklist();
    },
    [reloadChecklist],
  );

  const removeCheckEntry = useCallback(
    async (entryId: string) => {
      await api(`/api/checklist/${entryId}`, {
        method: "DELETE",
      }).catch(() => {});

      await reloadChecklist();
    },
    [reloadChecklist],
  );

  return {
    checklist,
    newCheckText,
    setNewCheckText,
    addCheckEntry,
    toggleCheckEntry,
    removeCheckEntry,
  };
}
