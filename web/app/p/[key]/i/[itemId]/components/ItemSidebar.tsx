"use client";
// YPJ-276(spec 0173):右栏易变信息仪表——录入件/信号/生命周期/工时汇总。
// 状态与回调全部上行 page(沿 TimeLogTab 模式);头部只留导航。
import {
  Box,
  Button,
  Chip,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { Dropdown } from "@/components/Dropdown";
import { DateInput } from "@/components/DateInput";
import { EstimateField } from "@/components/EstimateField";
import { AssignPopover } from "@/components/AssignPopover";
import { SignalChip } from "@/components/Verdict";
import type { CloseReason } from "@/lib/closeReason";
import type { EstimateScale } from "@/lib/estimate";
import { t } from "@/lib/texts";
import type {
  Item,
  Status,
  SprintLite,
  MilestoneLite,
  ItemTypeLite,
  Feasibility,
  TimeEntry,
} from "../types";

export function ItemSidebar({
  projectKey,
  item,
  statuses,
  itemId,
  onLoad,
  onMoveStatus,
  onAssign,
  onEditCloseReason,
  startDate,
  startTime,
  dueDate,
  dueTime,
  timelineLocked,
  onStartDate,
  onStartTime,
  onDueDate,
  onDueTime,
  priority,
  onPriorityChange,
  sprints,
  onSprintChange,
  milestones,
  onToggleMilestone,
  releaseId,
  releases,
  onReleaseChange,
  type,
  typeOptions,
  onTypeChange,
  estScales,
  estRequired,
  estValue,
  estUnit,
  onEstimateChange,
  estInvalid,
  onSave,
  saved,
  sprintHint,
  feasibility,
  timeLog,
  onGoTimelog,
}: {
  projectKey: string;
  item: Item;
  statuses: Status[];
  itemId: string;
  onLoad: () => void;
  onMoveStatus: (statusName: string) => void;
  onAssign: (memberId: string | null) => void;
  onEditCloseReason: () => void;
  startDate: string;
  startTime: string;
  dueDate: string;
  dueTime: string;
  timelineLocked: boolean;
  onStartDate: (v: string) => void;
  onStartTime: (v: string) => void;
  onDueDate: (v: string) => void;
  onDueTime: (v: string) => void;
  priority: string;
  onPriorityChange: (v: string) => void;
  sprints: SprintLite[];
  onSprintChange: (v: string | null) => void;
  milestones: MilestoneLite[];
  onToggleMilestone: (id: string) => void;
  releaseId: string | null;
  releases: { releaseId: string; name: string }[];
  onReleaseChange: (v: string | null) => void;
  type: string;
  typeOptions: ItemTypeLite[];
  onTypeChange: (v: string) => void;
  estScales: EstimateScale[];
  estRequired: boolean;
  estValue: string;
  estUnit: string;
  onEstimateChange: (v: string, u: string) => void;
  estInvalid: boolean;
  onSave: () => void;
  saved: boolean;
  sprintHint: string;
  feasibility: Feasibility | null;
  timeLog: { entries: TimeEntry[]; totalMinutes: number } | null;
  onGoTimelog: () => void;
}) {
  const isFinal =
    statuses.find((s) => s.name === item.status)?.statusType === "FINAL";
  const runningEntry = timeLog?.entries.find((e) => e.endedAt === null) ?? null;

  return (
    <Box sx={{ width: 280, flexShrink: 0 }} data-testid="item-sidebar">
      <Stack spacing={0.5}>
        {/* 状态(280 弹窗流入口逻辑不变;终态显示理由编辑 chip) */}
        <Dropdown
          ariaLabel="item 状态"
          size="small"
          value={item.status}
          onChange={(v) => onMoveStatus(v)}
          sx={{ minWidth: 160 }}
          options={statuses.map((s) => ({ value: s.name, label: s.name }))}
        />
        {isFinal && (
          <Tooltip title={t.closeReason.editTitle}>
            <Chip
              size="small"
              onClick={onEditCloseReason}
              sx={{ alignSelf: "flex-start" }}
              color={item.closeReason === "done" ? "default" : "warning"}
              label={
                (item.closeReason &&
                  t.closeReason[item.closeReason as CloseReason]) ??
                item.closeReason
              }
            />
          </Tooltip>
        )}
      </Stack>

      <Stack spacing={0.5} sx={{ mt: 2 }}>
        {/* 负责人(文本沿 e2e 惯例:「分配」空态 chip / 👤 chip) */}
        {item.assignee ? (
          <AssignPopover
            itemId={itemId}
            assignee={item.assignee}
            onChanged={onLoad}
          >
            {(onClick) => (
              <Chip
                size="small"
                label={`👤 ${item.assignee}`}
                onClick={onClick}
                onDelete={() => onAssign(null)}
                sx={{ alignSelf: "flex-start" }}
              />
            )}
          </AssignPopover>
        ) : (
          <AssignPopover itemId={itemId} assignee={null} onChanged={onLoad}>
            {(onClick) => (
              <Chip
                size="small"
                variant="outlined"
                label={t.assign.open}
                onClick={onClick}
                sx={{ opacity: 0.55, alignSelf: "flex-start" }}
              />
            )}
          </AssignPopover>
        )}
      </Stack>

      {/* 计划起止(YPJ-218:父持子时间线 → 四框禁用;DateInput 段宽固定+双图标≥188px,280 栏内竖排各占满宽) */}
      <Stack spacing={1} sx={{ mt: 2 }}>
        <DateInput
          label="开始日期"
          size="small"
          value={startDate}
          onChange={onStartDate}
          sx={{ width: "100%" }}
          disabled={timelineLocked}
        />
        <TextField
          label="开始时刻"
          size="small"
          type="time"
          value={startTime}
          onChange={(e) => onStartTime(e.target.value)}
          sx={{ width: "100%" }}
          slotProps={{ inputLabel: { shrink: true } }}
          disabled={timelineLocked}
        />
        <DateInput
          label="截止日期"
          size="small"
          value={dueDate}
          onChange={onDueDate}
          sx={{ width: "100%" }}
          disabled={timelineLocked}
        />
        <TextField
          label="截止时刻"
          size="small"
          type="time"
          value={dueTime}
          onChange={(e) => onDueTime(e.target.value)}
          sx={{ width: "100%" }}
          slotProps={{ inputLabel: { shrink: true } }}
          disabled={timelineLocked}
        />
        {timelineLocked && (
          <Typography variant="caption" color="text.secondary">
            已有子项,时间线由子项汇总
          </Typography>
        )}
      </Stack>

      {/* 预估(YPJ-186 预估三态;estInvalid 由 page 守卫) */}
      <Stack spacing={1} sx={{ mt: 2 }}>
        <EstimateField
          scales={estScales}
          required={estRequired}
          value={estValue}
          unit={estUnit}
          onChange={onEstimateChange}
        />
      </Stack>

      <Stack spacing={1} sx={{ mt: 2 }}>
        <Dropdown
          label={t.item.priority}
          size="small"
          value={priority}
          onChange={onPriorityChange}
          sx={{ width: 140 }}
          options={[
            { value: "", label: "无" },
            ...["P0", "P1", "P2", "P3"].map((p) => ({ value: p, label: p })),
          ]}
        />
        {sprints.length > 0 && (
          <Dropdown
            label={t.item.sprint}
            size="small"
            value={item.sprintId ?? ""}
            onChange={(v) => onSprintChange(v || null)}
            sx={{ width: 240 }}
            clearable
            options={sprints
              .filter(
                (s) => s.status !== "completed" || s.sprintId === item.sprintId,
              )
              .sort(
                (a, b) =>
                  ["active", "planned", "completed"].indexOf(a.status) -
                  ["active", "planned", "completed"].indexOf(b.status),
              )
              .map((s) => ({
                value: s.sprintId,
                label: s.name,
                disabled: s.status === "completed",
                chip: {
                  label:
                    t.projectNav.sprintStatus[
                      s.status as keyof typeof t.projectNav.sprintStatus
                    ],
                  color:
                    s.status === "active"
                      ? "primary"
                      : s.status === "planned"
                        ? "warning"
                        : "default",
                },
              }))}
          />
        )}
        {item.sprintId && (
          <Link
            href={`/p/${projectKey}/sprint/${item.sprintId}`}
            style={{ textDecoration: "none" }}
          >
            <Typography variant="caption" color="primary">
              {item.sprintName}
            </Typography>
          </Link>
        )}
        <Dropdown
          label={t.item.release}
          value={releaseId ?? ""}
          onChange={(v) => onReleaseChange(v || null)}
          clearable={releaseId !== null}
          sx={{ width: 240 }}
          options={[
            { value: "", label: t.releases.stageNone },
            ...releases.map((r) => ({ value: r.releaseId, label: r.name })),
            // 已挂的 release 已不在 planned(已发布)→ 兜底回显,防选中态丢 label
            ...(releaseId && !releases.some((r) => r.releaseId === releaseId)
              ? [{ value: releaseId, label: t.releases.released }]
              : []),
          ]}
        />
        <Dropdown
          label={t.item.type}
          value={type}
          onChange={onTypeChange}
          sx={{ width: 240 }}
          options={
            // YPJ-177:动态定义(name 显示);历史值不在定义内时兜底回显
            [
              ...typeOptions.map((tp) => ({
                value: tp.typeKey,
                label: tp.name,
              })),
              ...(type && !typeOptions.some((tp) => tp.typeKey === type)
                ? [{ value: type, label: type }]
                : []),
            ]
          }
        />
      </Stack>

      {milestones.length > 0 && (
        <Stack
          direction="row"
          spacing={0.5}
          alignItems="center"
          flexWrap="wrap"
          useFlexGap
          sx={{ mt: 2 }}
        >
          <Typography variant="caption" color="text.secondary">
            {t.item.milestones}
          </Typography>
          {milestones.map((ms) => (
            <Chip
              key={ms.milestoneId}
              data-testid="item-milestone-chip"
              label={ms.name}
              size="small"
              color={
                item?.milestoneIds?.includes(ms.milestoneId)
                  ? "primary"
                  : "default"
              }
              variant={
                item?.milestoneIds?.includes(ms.milestoneId)
                  ? "filled"
                  : "outlined"
              }
              onClick={() => onToggleMilestone(ms.milestoneId)}
            />
          ))}
        </Stack>
      )}

      {/* 保存行(载荷不变,批量 PATCH 含主列 title/description;YPJ-282 再做即时保存) */}
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 2 }}>
        <Button variant="contained" onClick={onSave} disabled={estInvalid}>
          {t.item.save}
        </Button>
        {saved && (
          <Typography variant="body2" color="text.secondary">
            {t.item.saved}
          </Typography>
        )}
      </Stack>
      {sprintHint && (
        <Typography variant="body2" color="success.main" sx={{ mt: 1 }}>
          {sprintHint}
        </Typography>
      )}

      {/* 信号区(可行性 + 期限/阻塞派生) */}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 3 }}>
        {t.sidebar.signals}
      </Typography>
      <Stack spacing={1} alignItems="flex-start" sx={{ mt: 0.5 }}>
        {feasibility && <SignalChip signal={feasibility.signal} />}
        {item.overdue && <Chip size="small" color="error" label="已超期" />}
        {item.dueSoon && !item.overdue && (
          <Chip size="small" color="warning" label="临期(3 天内)" />
        )}
        {item.blocked && <Chip size="small" color="error" label="被阻塞" />}
      </Stack>

      {/* 生命周期(YPJ-132 派生) */}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 2 }}>
        创建
      </Typography>
      <Typography variant="body2">
        {item.createdAt
          ? new Date(item.createdAt).toLocaleString("zh-CN")
          : "—"}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
        实际开始
      </Typography>
      <Typography variant="body2">
        {item.startedAt
          ? new Date(item.startedAt).toLocaleString("zh-CN")
          : "—"}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
        实际完成
      </Typography>
      <Typography variant="body2">
        {item.completedAt
          ? new Date(item.completedAt).toLocaleString("zh-CN")
          : "—"}
      </Typography>

      {/* 工时汇总(只读;录入全在工时 tab) */}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 2 }}>
        {t.time.section}
      </Typography>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
        <Typography variant="body2">
          {timeLog && timeLog.totalMinutes > 0
            ? t.time.fmt(timeLog.totalMinutes)
            : "—"}
        </Typography>
        {runningEntry && timeLog && (
          <Chip
            size="small"
            color="warning"
            label={`${t.time.running} ${t.time.fmt(
              timeLog.entries.find((e) => e.endedAt === null)!.minutes,
            )}`}
          />
        )}
      </Stack>
      <Button
        size="small"
        sx={{ alignSelf: "flex-start", mt: 0.5 }}
        onClick={onGoTimelog}
      >
        {t.sidebar.timelogDetail}
      </Button>
    </Box>
  );
}
