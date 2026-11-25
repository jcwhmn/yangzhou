// YPJ-257 页面重构(纯结构):工时域状态与动作自 page 下沉(reloadItem 供计时起止后回刷 item 工时合计)
import { useCallback, useEffect, useState } from "react";

import { api } from "@/lib/api";

import { t } from "@/lib/texts";

import type { TimeEntry, SuggestedEntry } from "../types";

export function useItemTime(
  itemId: string,
  setError: (msg: string) => void,
  reloadItem: () => Promise<void>,
) {
  const [timeLog, setTimeLog] = useState<{
    entries: TimeEntry[];
    totalMinutes: number;
  } | null>(null);

  const [manualMinutes, setManualMinutes] = useState("");

  const [manualNote, setManualNote] = useState("");

  const [suggestions, setSuggestions] = useState<SuggestedEntry[] | null>(null);

  const [suggestionsBusy, setSuggestionsBusy] = useState(false);

  useEffect(() => {
    if (!itemId) return;
    api<{ entries: TimeEntry[]; totalMinutes: number }>(
      `/api/items/${itemId}/time-entries`,
    )
      .then(setTimeLog)
      .catch(() => {});
  }, [itemId]);

  const startTimer = useCallback(async () => {
    setError("");

    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",
        body: JSON.stringify({ minutes: null }),
      });

      await reloadItem();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }, [itemId, reloadItem, setError]);

  const loadSuggestions = useCallback(async () => {
    setError("");

    setSuggestionsBusy(true);

    try {
      setSuggestions(
        await api<SuggestedEntry[]>(
          `/api/items/${itemId}/time-entry-suggestions`,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    } finally {
      setSuggestionsBusy(false);
    }
  }, [itemId, setError]);

  const logSuggestion = useCallback(
    async (s: SuggestedEntry) => {
      setError("");

      setSuggestionsBusy(true);

      try {
        await api(`/api/items/${itemId}/time-entries`, {
          method: "POST",

          body: JSON.stringify({
            minutes: null,
            startedAt: s.startedAt,
            endedAt: s.endedAt,
            note: s.note,
          }),
        });

        setSuggestions((prev) => (prev ?? []).filter((x) => x.ref !== s.ref));

        await reloadItem();
      } catch (e) {
        setError(e instanceof Error ? e.message : t.time.failed);
      } finally {
        setSuggestionsBusy(false);
      }
    },
    [itemId, reloadItem, setError],
  );

  const stopTimer = useCallback(
    async (entryId: string) => {
      setError("");

      try {
        await api(`/api/time-entries/${entryId}/stop`, { method: "POST" });

        await reloadItem();
      } catch (e) {
        setError(e instanceof Error ? e.message : t.time.failed);
      }
    },
    [itemId, reloadItem, setError],
  );

  const addManual = useCallback(async () => {
    const m = Number(manualMinutes);

    if (!m || m <= 0) return;

    setError("");

    try {
      await api(`/api/items/${itemId}/time-entries`, {
        method: "POST",

        body: JSON.stringify({ minutes: m, note: manualNote.trim() || null }),
      });

      setManualMinutes("");

      setManualNote("");

      await reloadItem();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.time.failed);
    }
  }, [itemId, manualMinutes, manualNote, reloadItem, setError]);

  return {
    timeLog,
    manualMinutes,
    setManualMinutes,
    manualNote,
    setManualNote,
    suggestions,
    suggestionsBusy,
    startTimer,
    stopTimer,
    addManual,
    loadSuggestions,
    logSuggestion,
  };
}
