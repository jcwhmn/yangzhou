"use client";
// YPJ-269(spec 0174):group 详情页右栏只读生命周期仪表——提取值零物化,字段来自 GET /groups/{id} 读时派生;
// 计划起止在 SprintHeader 已有,不双真相源(SRS §1)。
import { Box, Typography } from "@mui/material";
import { FactRow, fmtDateTime } from "@/components/FactRow";
import type { Sprint } from "../types";

function duration(
  startedAt?: string | null,
  completedAt?: string | null,
): string | null {
  if (!startedAt || !completedAt) return null;
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (ms <= 0) return null; // 防御:完成时刻理论上必晚于开始
  const totalMin = Math.floor(ms / 60000);
  const days = Math.floor(totalMin / 1440);
  const hours = Math.floor((totalMin % 1440) / 60);
  const minutes = totalMin % 60;
  if (days > 0) return `${days}天${hours}小时`;
  if (hours > 0) return `${hours}小时${minutes}分钟`;
  return `${minutes}分钟`;
}

export function GroupSidebar({ sprint }: { sprint: Sprint }) {
  return (
    <Box sx={{ width: 280, flexShrink: 0 }} data-testid="group-sidebar">
      <Typography variant="h6" gutterBottom>
        生命周期
      </Typography>
      <FactRow label="创建" value={fmtDateTime(sprint.createdAt)} />
      <FactRow label="实际开始" value={fmtDateTime(sprint.startedAt)} />
      <FactRow label="实际完成" value={fmtDateTime(sprint.completedAt)} />
      <FactRow
        label="执行时长"
        value={duration(sprint.startedAt, sprint.completedAt)}
      />
      <FactRow
        label="首 item 开始"
        value={fmtDateTime(sprint.firstItemStartedAt)}
      />
      <FactRow
        label="末 item 完成"
        value={fmtDateTime(sprint.lastItemCompletedAt)}
      />
    </Box>
  );
}
