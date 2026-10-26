"use client";

import { useEffect, useState } from "react";
import { Typography } from "@mui/material";
import { t } from "@/lib/texts";

/** YPJ-101:全站 footer——版权 + 构建期注入的版本号(/api/version,公开端点,无需登录)。 */
export function Footer() {
  const [version, setVersion] = useState(t.footer.versionFallback);

  useEffect(() => {
    let alive = true;
    fetch("/api/version")
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((d: { version: string }) => {
        if (alive && d.version) setVersion(d.version);
      })
      .catch(() => {
        /* 非 200 保持 fallback */
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <Typography
      variant="caption"
      color="text.secondary"
      sx={{ display: "block", textAlign: "center", py: 2, userSelect: "none" }}
    >
      {t.footer.copyright} · {version}
    </Typography>
  );
}
