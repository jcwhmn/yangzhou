"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Stack, Typography } from "@mui/material";
import { t } from "@/lib/texts";

export function AppNav() {
  const pathname = usePathname();
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
      <Typography component={Link} href="/members" sx={{ textDecoration: "none", color: "primary.main" }}>
        {t.nav.members}
      </Typography>
    </Stack>
  );
}
