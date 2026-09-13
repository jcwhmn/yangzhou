"use client";

import { Alert, Box, Button, Chip, Container, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Action = { statusItemId: string; statusName: string };
type NotificationRow = {
  notificationId: string;
  itemId: string;
  number: string;
  projectKey: string;
  title: string;
  kind: string;
  actor: string | null;
  oldValue: string | null;
  newValue: string | null;
  read: boolean;
  createdAt: string;
  action: Action | null;
};

/** 通知中心(V7-S2):列表 + 单条/全部已读 + 动作按钮(推进到下一列,点击调既有 move API)。 */
export default function NotificationsPage() {
  const [rows, setRows] = useState<NotificationRow[]>([]);
  const [error, setError] = useState("");
  const [advancing, setAdvancing] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await api<NotificationRow[]>("/api/notifications"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, []);

  useEffect(() => {
    load().catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [load]);

  async function markRead(notificationId: string) {
    await api(`/api/notifications/${notificationId}/read`, { method: "PUT" }).catch(() => {});
  }

  async function markAllRead() {
    setError("");
    try {
      await api("/api/notifications/read-all", { method: "POST" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    }
  }

  async function advance(n: NotificationRow) {
    if (!n.action) return;
    setError("");
    setAdvancing(n.notificationId);
    try {
      await api(`/api/items/${n.itemId}`, {
        method: "PATCH",
        body: JSON.stringify({ statusItemId: n.action.statusItemId }),
      });
      await markRead(n.notificationId);
      await load();
    } catch (e) {
      // 服务端复验失败(迁移表/WIP/开工须有主)原样冒出
      setError(e instanceof Error ? `${t.notif.advanceFailed}:${e.message}` : t.notif.advanceFailed);
    } finally {
      setAdvancing(null);
    }
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <AppNav />
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{t.notif.title}</Typography>
        <Button size="small" onClick={markAllRead} disabled={rows.every((r) => r.read)}>
          {t.notif.markAll}
        </Button>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {rows.length === 0 && (
        <Typography color="text.secondary">{t.notif.empty}</Typography>
      )}
      <Stack spacing={1}>
        {rows.map((n) => (
          <Box
            key={n.notificationId}
            sx={{
              p: 1.5,
              borderRadius: 1,
              border: "1px solid",
              borderColor: n.read ? "divider" : "primary.main",
              bgcolor: n.read ? "background.paper" : "action.hover",
            }}
            onClick={() => !n.read && markRead(n.notificationId)}
          >
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              {!n.read && <Chip size="small" color="error" label="●" sx={{ px: 0.5 }} />}
              <Chip size="small" variant="outlined" label={t.notif.kinds[n.kind] ?? n.kind} />
              <Chip size="small" label={n.number} />
              <Link href={`/p/${n.projectKey}/i/${n.itemId}`}>
                <Typography variant="body2" sx={{ textDecoration: "none" }}>
                  {n.title}
                </Typography>
              </Link>
              <Typography variant="caption" color="text.secondary" sx={{ ml: "auto" }}>
                {n.actor ? `${n.actor} · ` : "GitHub · "}
                {new Date(n.createdAt).toLocaleString("zh-CN")}
              </Typography>
            </Stack>
            {(n.oldValue || n.newValue) && (
              <Typography variant="caption" color="text.secondary" display="block">
                {n.oldValue ?? ""} → {n.newValue ?? ""}
              </Typography>
            )}
            {n.action && (
              <Button
                size="small"
                variant="contained"
                sx={{ mt: 1 }}
                disabled={advancing === n.notificationId}
                onClick={(e) => {
                  e.stopPropagation();
                  advance(n);
                }}
              >
                {t.notif.advanceTo(n.action.statusName)}
              </Button>
            )}
          </Box>
        ))}
      </Stack>
    </Container>
  );
}
