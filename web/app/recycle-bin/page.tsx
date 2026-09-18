"use client";

import { Button, Card, CardContent, Chip, Container, Stack, Typography } from "@mui/material";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type RecycleItem = {
  itemId: string;
  number: string;
  title: string;
  projectName: string;
  deletedAt: string | null;
};

/** V9-S6 回收站:软删 item 的恢复与彻底删除(项目级软删 defer)。 */
export default function RecycleBinPage() {
  const [rows, setRows] = useState<RecycleItem[] | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await api<RecycleItem[]>("/api/recycle-bin"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, []);

  useEffect(() => {
    load().catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [load]);

  async function restore(itemId: string) {
    setError("");
    setBusy(itemId);
    try {
      await api(`/api/recycle-bin/${itemId}/restore`, { method: "POST" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "恢复失败");
    } finally {
      setBusy(null);
    }
  }

  async function purge(itemId: string) {
    setError("");
    setBusy(itemId);
    try {
      await api(`/api/recycle-bin/${itemId}`, { method: "DELETE" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Typography variant="h5" gutterBottom>
        回收站
      </Typography>
      {error && (
        <Typography color="error" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}
      {rows !== null && rows.length === 0 && (
        <Typography color="text.secondary">回收站是空的。</Typography>
      )}
      <Stack spacing={1}>
        {(rows ?? []).map((r) => (
          <Card key={r.itemId} variant="outlined">
            <CardContent sx={{ py: 1.5, "&:last-child": { pb: 1.5 } }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Chip size="small" variant="outlined" label={r.number} />
                <Typography variant="body2">{r.title}</Typography>
                <Typography variant="caption" color="text.secondary" sx={{ ml: "auto" }}>
                  {r.projectName} · 删除于 {r.deletedAt ? new Date(r.deletedAt).toLocaleString("zh-CN") : "?"}
                </Typography>
                <Button size="small" variant="contained" disabled={busy === r.itemId} onClick={() => restore(r.itemId)}>
                  恢复
                </Button>
                <Button size="small" color="error" disabled={busy === r.itemId} onClick={() => purge(r.itemId)}>
                  彻底删除
                </Button>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>
    </Container>
  );
}
