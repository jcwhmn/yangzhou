"use client";
// YPJ-284:组状态迁移时间线(仿 item ActivityTab 简版,~40 行 caption 行)。
// 自取数 + 依赖 status 变化重拉(动作后页面已重拉详情,status 必变);ASC 时间线,存量组空态兜底。
import { useEffect, useState } from "react";
import { Box, Stack, Typography } from "@mui/material";
import { api } from "@/lib/api";
import { fmtDateTime } from "@/components/FactRow";

type Row = {
  objectId: string;
  from: string | null;
  to: string;
  source: string;
  createdAt: string;
};

/** 文案外置:source 中文标签(created=建组初始/action=动作/cascade=联动,SRS §3.1)。 */
const SOURCE_ZH: Record<string, string> = {
  created: "创建",
  action: "操作",
  cascade: "联动",
};

export function GroupHistoryLog({
  projectKey,
  groupId,
  status,
}: {
  projectKey: string;
  groupId: string;
  status?: string;
}) {
  const [rows, setRows] = useState<Row[] | null>(null);
  useEffect(() => {
    let alive = true;
    api<Row[]>(`/api/projects/${projectKey}/groups/${groupId}/status-history`)
      .then((r) => {
        if (alive) setRows(r);
      })
      .catch(() => {
        if (alive) setRows([]); // 取数失败按空态兜底,不阻塞页面
      });
    return () => {
      alive = false;
    };
  }, [projectKey, groupId, status]);
  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="h6" gutterBottom>
        状态迁移
      </Typography>
      <Stack spacing={0.5}>
        {(rows ?? []).map((r) => (
          <Typography key={r.objectId} variant="caption" color="text.secondary">
            {fmtDateTime(r.createdAt)} · {SOURCE_ZH[r.source] ?? r.source} ·{" "}
            {r.from ?? "—"} → {r.to}
          </Typography>
        ))}
        {rows !== null && rows.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            暂无迁移记录
          </Typography>
        )}
      </Stack>
    </Box>
  );
}
