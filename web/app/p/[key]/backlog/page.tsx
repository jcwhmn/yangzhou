"use client";

import {
  Button,
  Chip,
  Container,
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
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { AssignPopover } from "@/components/AssignPopover";

type Item = {
  itemId: string;
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
      await api(`/api/projects/${key}/items`, { method: "POST", body: JSON.stringify({ title: newTitle.trim() }) });
      setNewTitle("");
      setItems(await api<Item[]>(`/api/projects/${key}/backlog`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    }
  }

  const columns = ["编号", "标题", "优先级", "状态", "负责人", "开始", "截止", "可行性"];

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">{`${String(key)} · ${t.projectNav.backlog}`}</Typography>
        {items !== null && (
          <Typography color="text.secondary">{`${items.length} item`}</Typography>
        )}
        <Stack component="form" direction="row" spacing={1} sx={{ ml: "auto" }} onSubmit={addItem}>
          <TextField
            size="small"
            placeholder={t.board.addItem}
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
          />
          <Button type="submit" variant="outlined">
            {t.board.addItem}
          </Button>
        </Stack>
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
                    <Link href={`/p/${key}/i/${it.itemId}`}>
                      <Typography variant="body2">{it.number}</Typography>
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Link href={`/p/${key}/i/${it.itemId}`}>
                      <Typography variant="body2">{it.title}</Typography>
                    </Link>
                  </TableCell>
                  <TableCell>{it.priority ?? "—"}</TableCell>
                  <TableCell>{it.status}</TableCell>
                  <TableCell>
                    <AssignPopover
                      itemId={it.itemId}
                      assignee={it.assignee}
                      onChanged={() => api<Item[]>(`/api/projects/${key}/backlog`).then(setItems).catch(() => {})}
                    >
                      {(onClick) => (
                        <Chip
                          size="small"
                          variant="outlined"
                          label={it.assignee ? `👤 ${it.assignee}` : t.assign.none}
                          onClick={onClick}
                          sx={it.assignee ? undefined : { opacity: 0.55 }}
                        />
                      )}
                    </AssignPopover>
                  </TableCell>
                  <TableCell>{it.startDate ?? "—"}</TableCell>
                  <TableCell>
                    <span style={{ color: it.overdue ? "red" : it.dueSoon ? "orange" : "inherit" }}>
                      {it.dueDate ?? "—"}
                    </span>
                  </TableCell>
                  <TableCell>{it.feasSignal ? t.signal[it.feasSignal] : "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Container>
  );
}
