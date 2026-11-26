"use client";
// YPJ-269(spec 0174):group 详情页右栏只读生命周期仪表——字段来自 GET /groups/{id} 读时派生。
// 口径修订(2026-11-26 用户拍板):尽量不留白——史缺失(存量组)时实际开始回落首 item 开始、
// 实际完成(FINAL 组)回落末 item 完成;执行时长两态:FINAL 组=完成区间,进行中=已进行(now−开始)。
import { Box, Typography } from "@mui/material";
import { FactRow, fmtDateTime } from "@/components/FactRow";
import type { Sprint } from "../types";

function duration(ms: number): string {
  const totalMin = Math.floor(Math.max(0, ms) / 60000);
  const days = Math.floor(totalMin / 1440);
  const hours = Math.floor((totalMin % 1440) / 60);
  const minutes = totalMin % 60;
  if (days > 0) return `${days}天${hours}小时`;
  if (hours > 0) return `${hours}小时${minutes}分钟`;
  return `${minutes}分钟`;
}

export function GroupSidebar({
  sprint,
  final,
}: {
  sprint: Sprint;
  final: boolean;
}) {
  const started = sprint.startedAt ?? sprint.firstItemStartedAt;
  const completed =
    sprint.completedAt ?? (final ? sprint.lastItemCompletedAt : null);
  const dur =
    started && completed
      ? duration(new Date(completed).getTime() - new Date(started).getTime())
      : started && !final
        ? `进行中 ${duration(Date.now() - new Date(started).getTime())}`
        : null;
  return (
    <Box sx={{ width: 280, flexShrink: 0 }} data-testid="group-sidebar">
      <Typography variant="h6" gutterBottom>
        生命周期
      </Typography>
      <FactRow label="创建" value={fmtDateTime(sprint.createdAt)} />
      <FactRow label="实际开始" value={fmtDateTime(started)} />
      <FactRow label="实际完成" value={fmtDateTime(completed)} />
      <FactRow label="执行时长" value={dur} />
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
