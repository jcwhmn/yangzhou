"use client";

import {
  Box,
  Button,
  Checkbox,
  Chip,
  Container,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { AppNav } from "@/components/AppNav";
import { t } from "@/lib/texts";

type Status = { statusId: string; name: string; isFinal: boolean; position: number };
type Item = {
  itemId: string;
  number: string;
  title: string;
  status: string;
  assignee: string | null;
  feasSignal: Signal | null;
  startDate: string | null;
  dueDate: string | null;
  overdue: boolean;
  dueSoon: boolean;
  priority: string | null;
  blocked: boolean;
};
type Signal = "GREEN" | "YELLOW" | "RED";

/** V10-S4 表格视图:全部 item,列可 toggle(localStorage 持久),排序(编号/状态/优先级)。 */
export default function TableViewPage() {
  const { key } = useParams<{ key: string }>();
  const [items, setItems] = useState<Item[] | null>(null);
  const [sort, setSort] = useState("number");
  const [error, setError] = useState("");

  // 列可见性:localStorage 持久
  const [cols, setCols] = useState<Record<string, boolean>>(() => {
    if (typeof window === "undefined") return {};
    try {
      return JSON.parse(localStorage.getItem("yz-table-cols") ?? "{}");
    } catch {
      return {};
    }
  });

  useEffect(() => {
    localStorage.setItem("yz-table-cols", JSON.stringify(cols));
  }, [cols]);

  function toggleCol(c: string) {
    setCols((prev) => ({ ...prev, [c]: !(prev[c] ?? true) }));
  }

  const load = useCallback(async () => {
    try {
      setItems(await api<Item[]>(`/api/projects/${key}/items`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [key]);

  useEffect(() => {
    load().catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [load]);

  const columns: { id: string; label: string; visible: boolean }[] = [
    { id: "number", label: "编号", visible: true }, // 恒显
    { id: "title", label: "标题", visible: true }, // 恒显
    { id: "priority", label: "优先级", visible: cols.priority ?? false },
    { id: "status", label: "状态", visible: cols.status ?? true },
    { id: "assignee", label: "负责人", visible: cols.assignee ?? true },
    { id: "startDate", label: "开始", visible: cols.startDate ?? true },
    { id: "dueDate", label: "截止", visible: cols.dueDate ?? true },
    { id: "overdue", label: "超期", visible: cols.overdue ?? true },
    { id: "feasSignal", label: "可行性", visible: cols.feasSignal ?? true },
    { id: "blocked", label: "阻塞", visible: cols.blocked ?? false },
  ];

  const sorted = [...(items ?? [])].sort((a, b) => {
    if (sort === "priority") {
      const rank = (p: string | null) => (p ? 4 - Number(p.slice(1)) : 9);
      return rank(a.priority) - rank(b.priority) || a.number.localeCompare(b.number);
    }
    if (sort === "status") return a.status.localeCompare(b.status) || a.number.localeCompare(b.number);
    return a.number.localeCompare(b.number);
  });

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <AppNav />
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        <Typography variant="h5">{`${String(key)} · 表格`}</Typography>
        <Select size="small" value={sort} onChange={(e) => setSort(String(e.target.value))} sx={{ width: 150 }}>
          <MenuItem value="number">按编号</MenuItem>
          <MenuItem value="priority">按优先级</MenuItem>
          <MenuItem value="status">按状态</MenuItem>
        </Select>
        <Button size="small" component={Link} href={`/p/${key}`}>
          ← 看板
        </Button>
      </Stack>

      {/* 列 toggle */}
      <Stack direction="row" spacing={1} sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
        {columns
          .filter((c) => c.id !== "number" && c.id !== "title")
          .map((c) => (
            <Chip
              key={c.id}
              size="small"
              label={c.label}
              clickable
              color={(cols[c.id] ?? c.visible) ? "primary" : "default"}
              onClick={() => toggleCol(c.id)}
            />
          ))}
      </Stack>

      {items !== null && items.length === 0 && (
        <Typography color="text.secondary">暂无 item。</Typography>
      )}
      {items !== null && items.length > 0 && (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                {columns
                  .filter((c) => cols[c.id] ?? c.visible)
                  .map((c) => (
                    <TableCell key={c.id}>{c.label}</TableCell>
                  ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map((it) => (
                <TableRow key={it.itemId} hover>
                  {columns
                    .filter((c) => cols[c.id] ?? c.visible)
                    .map((c) => (
                      <TableCell key={c.id}>
                        {c.id === "number" ? (
                          <Link href={`/p/${key}/i/${it.itemId}`}>
                            <Typography variant="body2">{it.number}</Typography>
                          </Link>
                        ) : c.id === "title" ? (
                          <Link href={`/p/${key}/i/${it.itemId}`}>
                            <Typography variant="body2">{it.title}</Typography>
                          </Link>
                        ) : c.id === "priority" ? (
                          it.priority ?? "—"
                        ) : c.id === "assignee" ? (
                          it.assignee ?? "—"
                        ) : c.id === "startDate" ? (
                          it.startDate ?? "—"
                        ) : c.id === "dueDate" ? (
                          <span
                            style={{
                              color: it.overdue ? "red" : it.dueSoon ? "orange" : "inherit",
                            }}
                          >
                            {it.dueDate ?? "—"}
                          </span>
                        ) : c.id === "overdue" ? (
                          it.overdue ? "是" : "否"
                        ) : c.id === "feasSignal" ? (
                          it.feasSignal ? t.signal[it.feasSignal] : "—"
                        ) : c.id === "blocked" ? (
                          it.blocked ? "⛔" : "—"
                        ) : (
                          "—"
                        )}
                      </TableCell>
                    ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Container>
  );
}
