// YPJ-257 页面重构(纯结构):release 挂接域状态与动作自 page 下沉
// YPJ-201(US-R4,spec 0134):release 挂接——下拉只含 planned;released 历史不可达
import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

export type ReleaseLite = {
  releaseId: string;
  name: string;
  status: string;
};

export function useItemRelease(
  key: string,
  itemId: string,
  setError: (msg: string) => void,
) {
  const [releases, setReleases] = useState<ReleaseLite[]>([]);

  const [releaseId, setReleaseId] = useState<string | null>(null);

  useEffect(() => {
    if (!itemId) return;
    api<{ releaseId: string | null }>(`/api/items/${itemId}/release-membership`)
      .then((m) => setReleaseId(m.releaseId))
      .catch(() => setReleaseId(null));
  }, [itemId]);

  useEffect(() => {
    api<ReleaseLite[]>(`/api/projects/${key}/releases`)
      .then((rs) => setReleases(rs.filter((r) => r.status === "planned")))
      .catch(() => setReleases([]));
  }, [key]);

  const setReleaseMembership = useCallback(
    async (next: string | null) => {
      try {
        const m = await api<{ releaseId: string | null }>(
          `/api/items/${itemId}/release-membership`,
          {
            method: "PUT",
            body: JSON.stringify({ releaseId: next }),
          },
        );
        setReleaseId(m.releaseId);
      } catch (e) {
        setError(e instanceof Error ? e.message : "挂接失败");
      }
    },
    [itemId, setError],
  );

  return { releases, releaseId, setReleaseMembership };
}
