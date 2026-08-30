"use client";

import Link from "next/link";
import { Stack, Typography } from "@mui/material";
import { t } from "@/lib/texts";

export function AppNav() {
  return (
    <Stack direction="row" spacing={3} alignItems="center" sx={{ mb: 3, borderBottom: 1, borderColor: "divider", pb: 1 }}>
      <Typography variant="h6" component={Link} href="/" sx={{ textDecoration: "none", color: "inherit" }}>
        {t.appName}
      </Typography>
      <Typography component={Link} href="/" sx={{ textDecoration: "none", color: "primary.main" }}>
        {t.nav.projects}
      </Typography>
      <Typography component={Link} href="/capabilities" sx={{ textDecoration: "none", color: "primary.main" }}>
        {t.nav.capabilities}
      </Typography>
      <Typography component={Link} href="/attributes" sx={{ textDecoration: "none", color: "primary.main" }}>
        {t.nav.attributes}
      </Typography>
      <Typography component={Link} href="/members" sx={{ textDecoration: "none", color: "primary.main" }}>
        {t.nav.members}
      </Typography>
    </Stack>
  );
}
