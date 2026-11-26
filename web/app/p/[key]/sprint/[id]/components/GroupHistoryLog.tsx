"use client";
// YPJ-284(spec 0175):组时间线(仿 item ActivityTab 简版)——组迁移 + 成员进出,DESC 后进先见。
// 自取数;ver=页面数据版本号(sprintVer),任何影响时间线的动作都会 bump → 重拉。存量组经成员史即有数据,纯空显示空态。
import { useEffect, useState } from "react";
import { Box, Stack, Typography } from "@mui/material";
import { api } from "@/lib/api";
import { fmtDateTime } from "@/components/FactRow";

type Row = {
  objectId: string;
  kind: string; // status | member
  createdAt: string;
  from?: string | null;
  to?: string | null;
  source?: string | null;
  itemTitle?: string | null;
  action?: string | null; // join | leave
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
  ver,
}: {
  projectKey: string;
  groupId: string;
  ver: number;
}) {
  const [rows, setRows] = useState<Row[] | null>(null);
  useEffect(() => {
    let alive = true;
    api<Row[]>(`/api/projects/${projectKey}/groups/${groupId}/timeline`)
      .then((r) => {
        if (alive) setRows(r);
      })
      .catch(() => {
        if (alive) setRows([]); // 取数失败按空态兜底,不阻塞页面
      });
    return () => {
      alive = false;
    };
  }, [projectKey, groupId, ver]);
  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="h6" gutterBottom>
        时间线
      </Typography>
      <Stack spacing={0.5}>
        {(rows ?? []).map((r) => (
          <Typography key={r.objectId} variant="caption" color="text.secondary">
            {fmtDateTime(r.createdAt)} ·{" "}
            {r.kind === "member"
              ? `成员 · ${r.action === "leave" ? "−" : "+"} ${r.itemTitle ?? "—"}`
              : `${SOURCE_ZH[r.source ?? ""] ?? r.source} · ${r.from ?? "—"} → ${r.to}`}
          </Typography>
        ))}
        {rows !== null && rows.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            暂无记录
          </Typography>
        )}
      </Stack>
    </Box>
  );
}
