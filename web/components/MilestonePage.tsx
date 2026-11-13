"use client";

import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { DateInput } from "@/components/DateInput";
import { PageShell } from "@/components/PageShell";

export type Milestone = {
  milestoneId: string;
  name: string;
  description: string | null;
  owner: string | null;
  status: string;
  targetDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  updatedAt: string | null;
  /** 票 2(US-M9):零 gate 站双 null(不显)。 */
  gateDone: number | null;
  gateTotal: number | null;
};

type GateItem = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  final: boolean;
};

type ItemLite = {
  itemId: string;
  number: string;
  title: string;
  status: string;
};

type PoolMember = { memberId: string; displayName: string; virtual: boolean };

const statusIcon = {
  planned: "○",
  in_progress: "▶",
  completed: "✓",
  cancelled: "✗",
} as const;

const timelineColor = {
  planned: "text.secondary",
  in_progress: "primary.main",
  completed: "success.main",
  cancelled: "grey.400",
} as const;

/**
 * 票 3(US-M11,spec 0116 §3.3):自绘横向时间线——有 targetDate 站按日期落位,
 * 无日期入右侧「未排期」区;纯展示零依赖。
 * 落位纯算术(ISO 日期→epoch 天,首尾各留 4%);防重叠贪心分层:pct 差 < 8 换下一行,上限 3 行。
 */
function Timeline({ milestones }: { milestones: Milestone[] }) {
  if (milestones.length === 0) return null;
  const dated = milestones
    .filter((m) => m.targetDate)
    .sort((a, b) => a.targetDate!.localeCompare(b.targetDate!));
  const undated = milestones.filter((m) => !m.targetDate);
  const day = (s: string) => new Date(`${s}T00:00:00Z`).getTime() / 86400000;
  const start = dated.length ? day(dated[0].targetDate!) : 0;
  const end = dated.length ? day(dated[dated.length - 1].targetDate!) : 0;
  const STEP = 60;
  const rowLast: number[] = [];
  const nodes = dated.map((m) => {
    const d = day(m.targetDate!);
    const pct = start === end ? 50 : 4 + (92 * (d - start)) / (end - start);
    let lane = rowLast.findIndex((last) => pct - last >= 8);
    if (lane === -1) lane = Math.min(rowLast.length, 2);
    rowLast[lane] = pct;
    return { m, pct, lane };
  });
  return (
    <Box data-testid="milestone-timeline" sx={{ display: "flex", my: 2 }}>
      {dated.length > 0 && (
        <Box
          sx={{
            flex: 1,
            position: "relative",
            minWidth: 0,
            mr: 1,
            height: (Math.max(...nodes.map((n) => n.lane)) + 1) * STEP + 10,
          }}
        >
          <Box
            sx={{
              position: "absolute",
              left: 0,
              right: 0,
              top: 19,
              borderTop: "2px solid",
              borderColor: "grey.300",
            }}
          />
          {nodes.map(({ m, pct, lane }) => (
            <Box key={m.milestoneId}>
              {lane > 0 && (
                <Box
                  sx={{
                    position: "absolute",
                    left: `${pct}%`,
                    top: 21,
                    height: lane * STEP - 11,
                    borderLeft: "1px solid",
                    borderColor: "grey.200",
                  }}
                />
              )}
              <Box
                data-testid="timeline-node"
                sx={{
                  position: "absolute",
                  left: `${pct}%`,
                  top: lane * STEP + 10,
                  transform: "translateX(-50%)",
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  maxWidth: 96,
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center" }}>
                  <Typography
                    sx={{
                      width: 20,
                      height: 20,
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      borderRadius: "50%",
                      fontSize: 12,
                      lineHeight: 1,
                      color:
                        timelineColor[m.status as keyof typeof timelineColor],
                      border: m.overdue ? "1px solid" : "none",
                      borderColor: "error.main",
                    }}
                  >
                    {statusIcon[m.status as keyof typeof statusIcon]}
                  </Typography>
                  {m.overdue && (
                    <Typography variant="caption" color="error">
                      ⚠
                    </Typography>
                  )}
                </Box>
                <Typography variant="caption" noWrap sx={{ maxWidth: 96 }}>
                  {m.name}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {m.targetDate}
                </Typography>
              </Box>
            </Box>
          ))}
        </Box>
      )}
      {undated.length > 0 && (
        <Box
          data-testid="timeline-unscheduled"
          sx={{
            width: 130,
            flexShrink: 0,
            borderLeft: "1px dashed",
            borderColor: "grey.300",
            pl: 1,
          }}
        >
          <Typography variant="caption" color="text.secondary">
            {t.milestone.timeline.unscheduled}({undated.length})
          </Typography>
          {undated.map((m) => (
            <Box
              key={m.milestoneId}
              sx={{ display: "flex", alignItems: "center", gap: 0.5 }}
            >
              <Typography
                sx={{
                  fontSize: 12,
                  color: timelineColor[m.status as keyof typeof timelineColor],
                }}
              >
                {statusIcon[m.status as keyof typeof statusIcon]}
              </Typography>
              <Typography variant="caption" noWrap>
                {m.name}
              </Typography>
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}

/**
 * Milestone 页(spec 0116 票 1 / US-M6):管理弹窗退役,三组列表(当前=0..1 in_progress /
 * 未来=planned targetDate 升序 nulls last / 历史=终态按 updatedAt 降序)+ 新建/行内编辑表单
 * + 行内单向状态按钮 + 双徽标(overdue 红 / dueSoon 黄,后端算,与 item 同口径)。
 * 操作真值表:planned 可开始/取消;in_progress 可完成/取消;终态只读、删除置灰(API 409 守卫兜底)。
 */
export function MilestonePage() {
  const { key } = useParams<{ key: string }>();
  const [milestones, setMilestones] = useState<Milestone[]>([]);
  const [pool, setPool] = useState<PoolMember[]>([]);
  const [name, setName] = useState("");
  const [owner, setOwner] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [description, setDescription] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [error, setError] = useState("");
  // 票 2(US-M7/M9):站行「依据」展开区 + 挂接选择器;数据同源 PUT 全量替换
  const [gateOpenId, setGateOpenId] = useState<string | null>(null);
  const [gates, setGates] = useState<Record<string, GateItem[]>>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerItems, setPickerItems] = useState<ItemLite[]>([]);
  const [picked, setPicked] = useState<string[]>([]);

  const load = useCallback(async () => {
    try {
      const [ms, p] = await Promise.all([
        api<Milestone[]>(`/api/projects/${key}/milestones`),
        api<PoolMember[]>(`/api/projects/${key}/members`),
      ]);
      setMilestones(ms);
      setPool(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }, [key]);

  useEffect(() => {
    load();
  }, [load]);

  const reset = () => {
    setName("");
    setOwner("");
    setTargetDate("");
    setDescription("");
    setEditingId(null);
    setError("");
  };

  // YPJ-176:变更后广播,侧栏即时重拉(sprints-changed 同型;gate 挂接不影响侧栏显示,不播)
  const notify = () => {
    if (typeof window !== "undefined") {
      window.dispatchEvent(new Event("milestones-changed"));
    }
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      const body = {
        name: name.trim(),
        targetDate: targetDate || null,
        description,
        owner: owner || "",
      };
      if (editingId) {
        await api(`/api/projects/${key}/milestones/${editingId}`, {
          method: "PATCH",
          body: JSON.stringify(body),
        });
      } else {
        await api(`/api/projects/${key}/milestones`, {
          method: "POST",
          body: JSON.stringify(body),
        });
      }
      reset();
      notify();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function transition(m: Milestone, status: string) {
    try {
      await api(`/api/projects/${key}/milestones/${m.milestoneId}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setError("");
      notify();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function remove(m: Milestone) {
    if (confirmingId !== m.milestoneId) {
      setConfirmingId(m.milestoneId);
      return;
    }
    try {
      await api(`/api/projects/${key}/milestones/${m.milestoneId}`, {
        method: "DELETE",
      });
      setConfirmingId(null);
      notify();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function toggleGates(m: Milestone) {
    if (gateOpenId === m.milestoneId) {
      setGateOpenId(null);
      return;
    }
    setGateOpenId(m.milestoneId);
    try {
      const g = await api<GateItem[]>(
        `/api/projects/${key}/milestones/${m.milestoneId}/items`,
      );
      setGates((prev) => ({ ...prev, [m.milestoneId]: g }));
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  // 摘除/挂接后全量替换;load() 同步行上就绪度徽标
  async function saveGates(msId: string, ids: string[]) {
    try {
      const g = await api<GateItem[]>(
        `/api/projects/${key}/milestones/${msId}/items`,
        { method: "PUT", body: JSON.stringify({ itemIds: ids }) },
      );
      setGates((prev) => ({ ...prev, [msId]: g }));
      setError("");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  async function removeGate(msId: string, itemId: string) {
    await saveGates(
      msId,
      (gates[msId] ?? []).map((g) => g.itemId).filter((id) => id !== itemId),
    );
  }

  async function openPicker(msId: string) {
    try {
      const [items, cur] = await Promise.all([
        api<ItemLite[]>(`/api/projects/${key}/items`),
        api<GateItem[]>(`/api/projects/${key}/milestones/${msId}/items`),
      ]);
      setPickerItems(items);
      setPicked(cur.map((g) => g.itemId));
      setPickerOpen(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.milestone.failed);
    }
  }

  const current = milestones.filter((m) => m.status === "in_progress");
  const future = milestones
    .filter((m) => m.status === "planned")
    .sort((a, b) =>
      (a.targetDate ?? "9999").localeCompare(b.targetDate ?? "9999"),
    );
  const terminal = milestones
    .filter((m) => m.status === "completed" || m.status === "cancelled")
    .sort((a, b) => (b.updatedAt ?? "").localeCompare(a.updatedAt ?? ""));

  const row = (m: Milestone) => {
    const isTerminal = m.status === "completed" || m.status === "cancelled";
    return (
      <Box
        key={m.milestoneId}
        data-testid="milestone-item"
        sx={{ borderBottom: "1px solid", borderColor: "grey.100" }}
      >
        <Box
          data-testid="milestone-row"
          sx={{
            display: "flex",
            alignItems: "center",
            gap: 1,
            py: 0.5,
          }}
        >
          <Typography variant="body2" sx={{ width: 16, textAlign: "center" }}>
            {statusIcon[m.status as keyof typeof statusIcon]}
          </Typography>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography
              variant="body2"
              sx={{
                textDecoration:
                  m.status === "cancelled" ? "line-through" : "none",
              }}
            >
              {m.name}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t.milestone.status[m.status as keyof typeof t.milestone.status]}
              {m.targetDate ? ` · ${m.targetDate}` : ""}
            </Typography>
          </Box>
          {m.overdue && (
            <Typography
              variant="caption"
              color="error"
              data-testid="milestone-badge"
            >
              ⚠ {t.milestone.overdue}
            </Typography>
          )}
          {m.dueSoon && (
            <Typography
              variant="caption"
              color="warning.main"
              data-testid="milestone-badge"
            >
              ⚠ {t.milestone.dueSoon}
            </Typography>
          )}
          <Button
            size="small"
            data-testid="milestone-gates"
            onClick={() => toggleGates(m)}
          >
            {t.milestone.gates}
            {m.gateTotal ? ` ${m.gateDone}/${m.gateTotal}` : ""}
          </Button>
          {m.status === "planned" && (
            <>
              <Button size="small" onClick={() => transition(m, "in_progress")}>
                ▶ {t.milestone.start}
              </Button>
              <Button
                size="small"
                color="inherit"
                onClick={() => transition(m, "cancelled")}
              >
                ✗ {t.milestone.cancelMs}
              </Button>
            </>
          )}
          {m.status === "in_progress" && (
            <>
              <Button size="small" onClick={() => transition(m, "completed")}>
                ✓ {t.milestone.complete}
              </Button>
              <Button
                size="small"
                color="inherit"
                onClick={() => transition(m, "cancelled")}
              >
                ✗ {t.milestone.cancelMs}
              </Button>
            </>
          )}
          {!isTerminal && (
            <Tooltip title={t.milestone.edit}>
              <IconButton
                size="small"
                aria-label={t.milestone.edit}
                onClick={() => {
                  setEditingId(m.milestoneId);
                  setName(m.name);
                  setOwner(m.owner ?? "");
                  setTargetDate(m.targetDate ?? "");
                  setDescription(m.description ?? "");
                  setError("");
                }}
              >
                <EditIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
          <Tooltip title={t.milestone.delete}>
            <IconButton
              size="small"
              aria-label={t.milestone.delete}
              disabled={isTerminal}
              color={confirmingId === m.milestoneId ? "error" : "default"}
              onClick={() => remove(m)}
            >
              {confirmingId === m.milestoneId ? (
                <Typography variant="caption" color="error">
                  {t.milestone.confirmDelete}
                </Typography>
              ) : (
                <DeleteIcon fontSize="small" />
              )}
            </IconButton>
          </Tooltip>
        </Box>
        {gateOpenId === m.milestoneId && (
          <Box sx={{ px: 4, py: 0.5 }} data-testid="gate-panel">
            {(gates[m.milestoneId] ?? []).map((g) => (
              <Stack
                key={g.itemId}
                direction="row"
                spacing={1}
                alignItems="center"
                data-testid="gate-row"
                sx={{ py: 0.25 }}
              >
                <Typography variant="caption" sx={{ minWidth: 72 }}>
                  {g.number}
                </Typography>
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
                  {g.title}
                </Typography>
                <Typography
                  variant="caption"
                  color={g.final ? "success.main" : "text.secondary"}
                >
                  {g.final ? `✓ ${t.milestone.gateFinal}` : g.status}
                </Typography>
                <Tooltip title={t.milestone.attach}>
                  <IconButton
                    size="small"
                    aria-label={t.milestone.attach}
                    onClick={() => removeGate(m.milestoneId, g.itemId)}
                  >
                    <CloseIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Stack>
            ))}
            <Button
              size="small"
              data-testid="gate-attach"
              onClick={() => openPicker(m.milestoneId)}
            >
              + {t.milestone.attach}
            </Button>
          </Box>
        )}
      </Box>
    );
  };

  const groupHeader = (icon: string, label: string, count?: number) => (
    <Typography variant="subtitle2" sx={{ mt: 2, mb: 0.5 }}>
      {icon ? `${icon} ` : ""}
      {label}
      {count !== undefined ? ` (${count})` : ""}
    </Typography>
  );

  return (
    <PageShell>
      <Typography variant="h6" sx={{ mb: 2 }}>
        {t.projectNav.milestones}
      </Typography>
      <Timeline milestones={milestones} />
      <Box
        component="form"
        onSubmit={submit}
        sx={{
          display: "flex",
          flexWrap: "wrap",
          gap: 1,
          alignItems: "center",
          my: 2,
        }}
      >
        <TextField
          label={t.milestone.createName}
          size="small"
          value={name}
          onChange={(e) => setName(e.target.value)}
          sx={{ width: 200 }}
        />
        <TextField
          select
          label={t.milestone.owner}
          size="small"
          value={owner}
          onChange={(e) => setOwner(e.target.value)}
          sx={{ width: 140 }}
        >
          <MenuItem value="">{t.milestone.ownerNone}</MenuItem>
          {pool.map((p) => (
            <MenuItem key={p.memberId} value={p.memberId}>
              {p.displayName}
            </MenuItem>
          ))}
        </TextField>
        <DateInput
          label={t.milestone.targetDate}
          size="small"
          value={targetDate}
          onChange={setTargetDate}
        />
        <TextField
          label={t.milestone.description}
          size="small"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          sx={{ flex: 1, minWidth: 220 }}
        />
        <Button type="submit" variant="contained" disabled={!name.trim()}>
          {editingId ? t.milestone.save : t.milestone.create}
        </Button>
        {editingId && <Button onClick={reset}>{t.milestone.cancelMs}</Button>}
      </Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={0.5}>
        {milestones.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            {t.milestone.empty}
          </Typography>
        )}
        {milestones.length > 0 && groupHeader("▶", t.milestone.current)}
        {current.map(row)}
        {groupHeader("○", t.milestone.future, future.length)}
        {future.map(row)}
        {milestones.length > 0 && groupHeader("", t.milestone.history)}
        {terminal.map(row)}
      </Stack>
      <Dialog
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        maxWidth="xs"
        fullWidth
        data-testid="gate-picker"
      >
        <DialogTitle>{t.milestone.pickerTitle}</DialogTitle>
        <DialogContent dividers>
          {pickerItems.map((it) => (
            <Stack
              key={it.itemId}
              direction="row"
              spacing={1}
              alignItems="center"
            >
              <Checkbox
                checked={picked.includes(it.itemId)}
                onChange={(e) =>
                  setPicked((prev) =>
                    e.target.checked
                      ? [...prev, it.itemId]
                      : prev.filter((id) => id !== it.itemId),
                  )
                }
              />
              <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
                {it.number} {it.title}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {it.status}
              </Typography>
            </Stack>
          ))}
          {pickerItems.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t.milestone.empty}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPickerOpen(false)}>
            {t.milestone.cancelMs}
          </Button>
          <Button
            variant="contained"
            data-testid="gate-picker-save"
            onClick={() => {
              if (gateOpenId) saveGates(gateOpenId, picked);
              setPickerOpen(false);
            }}
          >
            {t.milestone.save}
          </Button>
        </DialogActions>
      </Dialog>
    </PageShell>
  );
}
