"use client";

import { Box, Button, Card, CardContent, Chip, Stack, TextField, Typography } from "@mui/material";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import type { Signal } from "@/components/Verdict";

type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  type: string;
  status: string;
  parentItemId: string | null;
  requirements: { attribute: string; minLevel: number | null }[];
};
type Feasibility = { signal: Signal };

export default function BoardPage() {
  const { key } = useParams<{ key: string }>();
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [feas, setFeas] = useState<Record<string, Signal>>({});
  const [newTitle, setNewTitle] = useState("");
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const project = await api<{ statuses: Status[] }>(`/api/projects/${key}`);
    setStatuses(project.statuses);
    const list = await api<Item[]>(`/api/projects/${key}/items`);
    setItems(list);
    const map: Record<string, Signal> = {};
    await Promise.all(
      list.map(async (it) => {
        try {
          map[it.itemId] = (await api<Feasibility>(`/api/items/${it.itemId}/feasibility`)).signal;
        } catch {
          /* 单项失败不拦看板 */
        }
      }),
    );
    setFeas(map);
  }, [key]);

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, [load]);

  async function addItem(e: React.FormEvent) {
    e.preventDefault();
    if (!newTitle.trim()) return;
    try {
      await api(`/api/projects/${key}/items`, { method: "POST", body: JSON.stringify({ title: newTitle.trim() }) });
      setNewTitle("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  async function moveTo(itemId: string, status: Status) {
    const item = items.find((i) => i.itemId === itemId);
    if (!item || item.status === status.name) return;
    try {
      // 乐观更新 + 失败回滚
      setItems((prev) => prev.map((i) => (i.itemId === itemId ? { ...i, status: status.name } : i)));
      await api(`/api/items/${itemId}`, { method: "PATCH", body: JSON.stringify({ statusItemId: status.statusId }) });
      await load();
    } catch (err) {
      setError(`${t.board.moveFailed}:${err instanceof Error ? err.message : ""}`);
      await load();
    }
  }

  const statusById = new Map(statuses.map((s) => [s.statusId, s]));
  // 卡片当前列:按名字匹配(status 字段是名字)
  const byStatusName = new Map<string, Item[]>();
  items.forEach((it) => {
    const list = byStatusName.get(it.status) ?? [];
    list.push(it);
    byStatusName.set(it.status, list);
  });
  void statusById;

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">
          {String(key).toUpperCase()} · 看板
        </Typography>
        <Stack component="form" direction="row" spacing={1} onSubmit={addItem}>
          <TextField size="small" placeholder={t.board.addItem} value={newTitle} onChange={(e) => setNewTitle(e.target.value)} />
          <Button type="submit" variant="outlined">
            {t.board.addItem}
          </Button>
        </Stack>
      </Stack>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      <Stack direction="row" spacing={2} sx={{ overflowX: "auto", alignItems: "flex-start", pb: 2 }}>
        {statuses.map((s) => (
          <Box
            key={s.statusId}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(s.statusId);
            }}
            onDragLeave={() => setDragOver((cur) => (cur === s.statusId ? null : cur))}
            onDrop={(e) => {
              e.preventDefault();
              setDragOver(null);
              const itemId = e.dataTransfer.getData("text/plain");
              if (itemId) moveTo(itemId, s);
            }}
            sx={{
              minWidth: 280,
              minHeight: 400,
              p: 1,
              borderRadius: 2,
              bgcolor: dragOver === s.statusId ? "action.hover" : "background.default",
              border: "1px dashed",
              borderColor: dragOver === s.statusId ? "primary.main" : "divider",
            }}
          >
            <Typography variant="subtitle2" sx={{ px: 1, py: 0.5 }}>
              {s.name}( {(byStatusName.get(s.name) ?? []).length} )
            </Typography>
            <Stack spacing={1}>
              {(byStatusName.get(s.name) ?? []).map((it) => (
                <Card
                  key={it.itemId}
                  draggable
                  onDragStart={(e) => e.dataTransfer.setData("text/plain", it.itemId)}
                  sx={{ cursor: "grab" }}
                >
                  <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
                    <Stack spacing={0.5}>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Chip label={it.number} size="small" variant="outlined" />
                        <Chip label={feas[it.itemId] ? t.signal[feas[it.itemId]] : ""} size="small" color={feas[it.itemId] === "RED" ? "error" : feas[it.itemId] === "YELLOW" ? "warning" : "success"} variant="outlined" sx={{ visibility: feas[it.itemId] ? "visible" : "hidden" }} />
                      </Stack>
                      <Link href={`/p/${key}/i/${it.itemId}`} style={{ textDecoration: "none", color: "inherit" }}>
                        <Typography variant="body2" sx={{ wordBreak: "break-word" }}>
                          {it.title}
                        </Typography>
                      </Link>
                    </Stack>
                  </CardContent>
                </Card>
              ))}
            </Stack>
          </Box>
        ))}
      </Stack>
    </Box>
  );
}
