"use client";

import { Badge, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

/** 导航铃铛(V7-S2):未读数 30s 轮询 + 路由切换刷新;未登录跳过。 */
export function AppNav() {
  const pathname = usePathname();
  const [unread, setUnread] = useState(0);

  const refresh = useCallback(async () => {
    if (typeof window === "undefined" || !localStorage.getItem("yz-token")) return;
    try {
      const res = await api<{ count: number }>("/api/notifications/unread-count");
      setUnread(res.count);
    } catch {
      /* 静默:铃铛只是提示,不因它打断页面 */
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 30000);
    return () => clearInterval(timer);
  }, [refresh, pathname]);

  const items = [
    { href: "/", label: t.nav.projects },
    { href: "/capabilities", label: t.nav.capabilities },
    { href: "/attributes", label: t.nav.attributes },
    { href: "/members", label: t.nav.members },
  ];
  return (
    <Stack direction="row" spacing={3} alignItems="center" sx={{ mb: 3, borderBottom: 1, borderColor: "divider", pb: 1 }}>
      <Typography variant="h6" component={Link} href="/" sx={{ textDecoration: "none", color: "inherit" }}>
        {t.appName}
      </Typography>
      {items.map((it) => {
        const active = pathname === it.href;
        return (
          <Typography
            key={it.href}
            component={Link}
            href={it.href}
            sx={{ textDecoration: "none", color: active ? "primary.main" : "text.secondary", fontWeight: active ? 700 : 400 }}
          >
            {it.label}
          </Typography>
        );
      })}
      <Typography
        component={Link}
        href="/notifications"
        sx={{ ml: "auto", textDecoration: "none", color: pathname === "/notifications" ? "primary.main" : "text.secondary" }}
        aria-label={t.notif.bell}
      >
        <Badge badgeContent={unread} color="error" max={99}>
          🔔
        </Badge>
      </Typography>
    </Stack>
  );
}
