// YPJ-257 页面重构(纯结构):依赖域状态与动作自 page 下沉
import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

import type { Dep } from "../types";

export function useItemDependencies(
  itemId: string,
  setError: (msg: string) => void,
) {
  const [deps, setDeps] = useState<Dep[] | null>(null);

  const [depBlocked, setDepBlocked] = useState(false);

  const [depAddOpen, setDepAddOpen] = useState(false);

  useEffect(() => {
    if (!itemId) return;
    api<{ dependencies: Dep[]; blocked: boolean }>(
      `/api/items/${itemId}/dependencies`,
    )
      .then((d) => {
        setDeps(d.dependencies);
        setDepBlocked(d.blocked);
      })
      .catch(() => {});
  }, [itemId]);

  const addDependency = useCallback(
    async (dependsOnItemId: string) => {
      if (!dependsOnItemId) return;

      setError("");

      try {
        await api(`/api/items/${itemId}/dependencies`, {
          method: "POST",

          body: JSON.stringify({ dependsOnItemId }),
        });

        setDepAddOpen(false);

        const d = await api<{ dependencies: Dep[]; blocked: boolean }>(
          `/api/items/${itemId}/dependencies`,
        );

        setDeps(d.dependencies);
        setDepBlocked(d.blocked);
      } catch (e) {
        setError(e instanceof Error ? e.message : "添加失败");
      }
    },
    [itemId, setError],
  );

  const removeDep = useCallback(
    async (rowId: string) => {
      await api(`/api/dependencies/${rowId}`, { method: "DELETE" }).catch(
        () => {},
      );

      const d = await api<{ dependencies: Dep[]; blocked: boolean }>(
        `/api/items/${itemId}/dependencies`,
      );

      setDeps(d.dependencies);
      setDepBlocked(d.blocked);
    },
    [itemId],
  );

  return {
    deps,
    depBlocked,
    depAddOpen,
    setDepAddOpen,
    addDependency,
    removeDep,
  };
}
