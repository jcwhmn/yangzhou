"use client";
// YPJ-269(spec 0174):最小事实行——caption 标签 + body2 值,空值显示"—"。
// 首用 = GroupSidebar;ItemSidebar 主体不动(282 即时保存票到时统一换)。
import { Typography } from "@mui/material";

/** zh-CN 本地化时刻;空值返回"—"(与 ItemSidebar 生命周期段同格式)。 */
export function fmtDateTime(iso?: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("zh-CN");
}

export function FactRow({
  label,
  value,
}: {
  label: string;
  value?: string | null;
}) {
  return (
    <>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ mt: 2, display: "block" }}
      >
        {label}
      </Typography>
      <Typography variant="body2">{value ?? "—"}</Typography>
    </>
  );
}
