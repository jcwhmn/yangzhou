"use client";

import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import { createPortal } from "react-dom";
import { useProjectToolbarSlot } from "@/components/ProjectToolbar";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { AssignPopover } from "@/components/AssignPopover";
import { fmtDate } from "@/lib/dates";

type Item = {
  itemId: string;
  parentNumber?: string | null;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  feasSignal: "GREEN" | "YELLOW" | "RED" | null;
  startDate: string | null;
  dueDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  priority: string | null;
};

/** V12-S2 Backlog 派生桶:非终态 ∧ 未进任何 planned/active sprint(后端算谓词,本页只读渲染,裁决 5)。 */
export default function BacklogPage() {
  const { key } = useParams<{ key: string }>();
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState("");
  const [newTitle, setNewTitle] = useState("");
  // YPJ-216(SRS 途径②):拉入缺预估 → 补录对话框,提交后自动重试拉入(不弹警告)
  const [supplement, setSupplement] = useState<{
    itemId: string;
    title: string;
    v: string;
    u: string;
  } | null>(null);
  const { slotRef } = useProjectToolbarSlot();

  async function pullToBoard(itemId: string, title = "") {
    try {
      await api(`/api/items/${itemId}/board-membership`, {
        method: "PUT",
        body: JSON.stringify({ onBoard: true }),
      });
      setSupplement(null);
      api<Item[]>(`/api/projects/${key}/backlog`)
        .then(setItems)
        .catch(() => {});
    } catch (e) {
      const msg = e instanceof Error ? e.message : "拉入失败";
      if (msg.includes("预估")) {
        // 守卫缺预估:弹补录对话框而非报错(SRS 途径②)
        const it = (items ?? []).find((x) => x.itemId === itemId);
        setSupplement({ itemId, title: it?.title || title, v: "", u: "hour" });
      } else {
        setError(msg);
      }
    }
  }

  async function submitSupplement() {
    if (!supplement?.v) return;
    try {
      await api(`/api/items/${supplement.itemId}`, {
        method: "PATCH",
        body: JSON.stringify({
          estimateValue: supplement.v,
          estimateUnit: supplement.u,
        }),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "预估保存失败");
      return;
    }
    await pullToBoard(supplement.itemId);
  }

  useEffect(() => {
    api<Item[]>(`/api/projects/${key}/backlog`)
      .then(setItems)
      .catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [key]);

  // V13-S4:Backlog 就地建项(零成员关系,天然入池)
  async function addItem(e: React.FormEvent) {
    e.preventDefault();
    if (!newTitle.trim()) return;
    try {
      await api(`/api/projects/${key}/items`, {
        method: "POST",
        body: JSON.stringify({ title: newTitle.trim() }),
      });
      setNewTitle("");
      setItems(await api<Item[]>(`/api/projects/${key}/backlog`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  const columns = [
    "编号",
    "标题",
    "优先级",
    "状态",
    "负责人",
    "拉入",
    "开始",
    "截止",
    "可行性",
  ];

  return (
    <PageShell>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{`${String(key)} · ${t.projectNav.backlog}`}</Typography>
        {items !== null && (
          <Typography color="text.secondary">{`${items.length} item`}</Typography>
        )}
        {/* YPJ-187:快速添加 portal 进统一顶栏(根 item 直建,语义不变) */}
        {slotRef.current &&
          createPortal(
            <Stack
              component="form"
              direction="row"
              spacing={1}
              onSubmit={addItem}
            >
              <TextField
                size="small"
                placeholder={t.board.addItem}
                value={newTitle}
                onChange={(e) => setNewTitle(e.target.value)}
              />
              <Button type="submit" variant="outlined">
                {t.board.addItem}
              </Button>
            </Stack>,
            slotRef.current,
          )}
      </Stack>
      {error && <Typography color="error">{error}</Typography>}
      {items !== null && items.length === 0 && (
        <Typography color="text.secondary">暂无未规划 item。</Typography>
      )}
      {items !== null && items.length > 0 && (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                {columns.map((c) => (
                  <TableCell key={c}>{c}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((it) => (
                <TableRow key={it.itemId} hover>
                  <TableCell>
                    <Link href={`/p/${key}/i/${it.itemId}?from=backlog`}>
                      <Typography variant="body2" sx={{ whiteSpace: "nowrap" }}>
                        {(it.parentNumber ? it.parentNumber + " › " : "") +
                          it.number}
                      </Typography>
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Link href={`/p/${key}/i/${it.itemId}?from=backlog`}>
                      <Typography variant="body2">{it.title}</Typography>
                    </Link>
                  </TableCell>
                  <TableCell>{it.priority ?? "—"}</TableCell>
                  <TableCell>{it.status}</TableCell>
                  <TableCell>
                    <AssignPopover
                      itemId={it.itemId}
                      assignee={it.assignee}
                      onChanged={() =>
                        api<Item[]>(`/api/projects/${key}/backlog`)
                          .then(setItems)
                          .catch(() => {})
                      }
                    >
                      {(onClick) => (
                        <Chip
                          size="small"
                          variant="outlined"
                          label={
                            it.assignee ? `👤 ${it.assignee}` : t.assign.none
                          }
                          onClick={onClick}
                          sx={it.assignee ? undefined : { opacity: 0.55 }}
                        />
                      )}
                    </AssignPopover>
                  </TableCell>
                  <TableCell>
                    <Button
                      size="small"
                      variant="outlined"
                      onClick={() => pullToBoard(it.itemId, it.title)}
                    >
                      {t.board.pullItem}
                    </Button>
                  </TableCell>
                  <TableCell>{fmtDate(it.startDate) || "—"}</TableCell>
                  <TableCell>
                    <span
                      style={{
                        color: it.overdue
                          ? "red"
                          : it.dueSoon
                            ? "orange"
                            : "inherit",
                      }}
                    >
                      {fmtDate(it.dueDate) || "—"}
                    </span>
                  </TableCell>
                  <TableCell>
                    {it.feasSignal ? t.signal[it.feasSignal] : "—"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {/* YPJ-216(SRS 途径②):缺预估补录对话框——提交后自动重试拉入 */}
      <Dialog open={supplement != null} onClose={() => setSupplement(null)} maxWidth="xs" fullWidth>
        <DialogTitle>补录预估</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            「{supplement?.title}」拉入需要预估时长,请补录:
          </Typography>
          <Stack direction="row" spacing={1}>
            <TextField
              size="small"
              label="预估"
              value={supplement?.v ?? ""}
              onChange={(e) =>
                setSupplement((s) => (s ? { ...s, v: e.target.value } : s))
              }
              fullWidth
            />
            <TextField
              size="small"
              select
              label="单位"
              value={supplement?.u ?? "hour"}
              onChange={(e) =>
                setSupplement((s) => (s ? { ...s, u: e.target.value } : s))
              }
              sx={{ width: 100 }}
            >
              {["hour", "day", "week"].map((u) => (
                <MenuItem key={u} value={u}>
                  {u}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSupplement(null)}>取消</Button>
          <Button variant="contained" onClick={submitSupplement} disabled={!supplement?.v}>
            确定并拉入
          </Button>
        </DialogActions>
      </Dialog>
    </PageShell>
  );
}
