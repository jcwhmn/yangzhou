"use client";

import { Badge, Button, Menu, Stack, Typography } from "@mui/material";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { subscribeUnread } from "@/lib/notifications-store";
import { t } from "@/lib/texts";

type Fav = { projectId: string; key: string; name: string };

/** 全站导航(V9-S2:根 layout 持久挂载;铃铛订阅单例 store;⭐ 收藏下拉)。 */
export function AppNav() {
  const pathname = usePathname();
  const [unread, setUnread] = useState(0);
  const [favAnchor, setFavAnchor] = useState<HTMLElement | null>(null);
  const [favs, setFavs] = useState<Fav[]>([]);

  useEffect(() => subscribeUnread(setUnread), []);

  const loadFavs = useCallback(async () => {
    try {
      setFavs(await api<Fav[]>("/api/favorites"));
    } catch {
      setFavs([]);
    }
  }, []);

  const openFavs = useCallback(
    (e: React.MouseEvent<HTMLElement>) => {
      setFavAnchor(e.currentTarget);
      loadFavs();
    },
    [loadFavs],
  );

  async function removeFav(projectKey: string) {
    try {
      await api(`/api/projects/${projectKey}/favorite`, { method: "DELETE" });
      await loadFavs();
    } catch {
      /* 静默 */
    }
  }

  if (pathname === "/login") return null;

  const links = [
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
      {links.map((it) => {
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
      <Button color="inherit" size="small" onClick={openFavs}>
        {t.nav.favorites}
      </Button>
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

      <Menu anchorEl={favAnchor} open={favAnchor !== null} onClose={() => setFavAnchor(null)}>
        {favs.length === 0 && (
          <Typography variant="caption" sx={{ px: 2, py: 1, display: "block" }}>
            {t.fav.empty}
          </Typography>
        )}
        {favs.map((f) => (
          <Stack key={f.projectId} direction="row" spacing={1} alignItems="center" sx={{ px: 2, py: 0.5 }}>
            <Link href={`/p/${f.key}`} style={{ textDecoration: "none", color: "inherit" }}>
              <Typography variant="body2">⭐ {f.name}</Typography>
            </Link>
            <Button size="small" color="error" onClick={() => removeFav(f.key)}>
              {t.fav.remove}
            </Button>
          </Stack>
        ))}
      </Menu>
    </Stack>
  );
}
