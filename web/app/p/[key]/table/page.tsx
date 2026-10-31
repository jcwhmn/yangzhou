"use client";

import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TableSortLabel,
  TextField,
  Typography,
} from "@mui/material";
import { PageShell } from "@/components/PageShell";
import { Dropdown } from "@/components/Dropdown";
import { ColumnConfigPopover } from "@/components/ColumnConfigPopover";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { AssignPopover } from "@/components/AssignPopover";

type Status = { statusId: string; name: string; position: number };
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
  blocked: boolean;
  sprintName: string | null;
};
type ViewDto = {
  viewId: string;
  name: string;
  filters: Record<string, string | null>;
  columns: Record<string, boolean>;
};
type Filters = { status: string; assignee: string; priority: string };

const EMPTY_FILTERS: Filters = { status: "", assignee: "", priority: "" };

type SortKey = { field: string; dir: "asc" | "desc" };

const DEFAULT_SORTS: SortKey[] = [{ field: "number", dir: "asc" }];

/** V10-S4 表格视图;YPJ-47 视图配置(spec 0045):筛选(客户端)+ 视图保存/切换 + 列配置 Popover(YPJ-28 合并)。 */
export default function TableViewPage() {
  const { key } = useParams<{ key: string }>();
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [sorts, setSorts] = useState<SortKey[]>(DEFAULT_SORTS);
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [error, setError] = useState("");

  // 列可见性:默认视图走 localStorage(按项目 key 区分,YPJ-32);选中视图时以视图数据为准
  function readCols(k: string): Record<string, boolean> {
    if (typeof window === "undefined") return {};
    try {
      return JSON.parse(localStorage.getItem(`yz-table-cols:${k}`) ?? "{}");
    } catch {
      return {};
    }
  }

  const [cols, setCols] = useState<Record<string, boolean>>(() =>
    readCols(key),
  );

  // 视图状态
  const [views, setViews] = useState<ViewDto[]>([]);
  const [selectedView, setSelectedView] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [nameOpen, setNameOpen] = useState<null | "save" | "saveAs">(null);
  const [viewName, setViewName] = useState("");

  useEffect(() => {
    setCols(readCols(key));
    setFilters(EMPTY_FILTERS);
    setSelectedView(null);
    setDirty(false);
  }, [key]);

  useEffect(() => {
    localStorage.setItem(`yz-table-cols:${key}`, JSON.stringify(cols));
  }, [cols, key]);

  const load = useCallback(async () => {
    try {
      const [its, sts, vs] = await Promise.all([
        api<Item[]>(`/api/projects/${key}/items`),
        api<{ statuses: Status[] }>(`/api/projects/${key}`).then(
          (p) => p.statuses,
        ),
        api<ViewDto[]>(`/api/projects/${key}/views`).catch(
          () => [] as ViewDto[],
        ),
      ]);
      setItems(its);
      setStatuses(sts);
      setViews(vs);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [key]);

  useEffect(() => {
    load().catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [load]);

  function toggleCol(c: string) {
    setCols((prev) => ({
      ...prev,
      [c]: !(prev[c] ?? columns.find((x) => x.id === c)?.visible ?? true),
    }));
    setDirty(true);
  }

  function setFilter(f: Partial<Filters>) {
    setFilters((prev) => ({ ...prev, ...f }));
    setDirty(true);
  }

  function applyView(v: ViewDto) {
    setSelectedView(v.viewId);
    setFilters({
      status: v.filters.status ?? "",
      assignee: v.filters.assignee ?? "",
      priority: v.filters.priority ?? "",
    });
    setCols({ ...v.columns });
    setDirty(false);
  }

  async function saveCurrent(name: string) {
    const body = {
      name,
      filters: {
        status: filters.status || null,
        assignee: filters.assignee || null,
        priority: filters.priority || null,
      },
      columns: cols,
    };
    try {
      if (selectedView && nameOpen === "save") {
        await api(`/api/projects/${key}/views/${selectedView}`, {
          method: "PATCH",
          body: JSON.stringify(body),
        });
      } else {
        const v = await api<ViewDto>(`/api/projects/${key}/views`, {
          method: "POST",
          body: JSON.stringify(body),
        });
        setViews((prev) => [...prev, v]);
        setSelectedView(v.viewId);
      }
      setDirty(false);
      setNameOpen(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  }

  async function deleteView() {
    if (!selectedView) return;
    try {
      await api(`/api/projects/${key}/views/${selectedView}`, {
        method: "DELETE",
      });
      setViews((prev) => prev.filter((v) => v.viewId !== selectedView));
      setSelectedView(null);
      setFilters(EMPTY_FILTERS);
      setCols(readCols(key));
      setDirty(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    }
  }

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
    { id: "sprint", label: "Sprint", visible: cols.sprint ?? true },
  ];

  const visibleCols = columns.filter(
    (c) => c.id === "number" || c.id === "title" || (cols[c.id] ?? c.visible),
  );

  const assignees = [
    ...new Set(
      (items ?? [])
        .map((i) => i.assignee)
        .filter((a): a is string => a != null),
    ),
  ];

  // 多栏排序:sorts 顺序即优先级;null 恒排最后,不随方向翻转(YPJ-124)
  type Val = string | number | boolean;
  const getters: Record<string, (i: Item) => Val | null> = {
    number: (i) => i.number,
    title: (i) => i.title,
    priority: (i) => i.priority,
    status: (i) => i.status,
    assignee: (i) => i.assignee,
    startDate: (i) => i.startDate,
    dueDate: (i) => i.dueDate,
    overdue: (i) => i.overdue,
    feasSignal: (i) => i.feasSignal,
    blocked: (i) => i.blocked,
    sprint: (i) => i.sprintName,
  };
  const valueRank: Record<string, Record<string, number>> = {
    priority: { P0: 0, P1: 1, P2: 2, P3: 3 },
    feasSignal: { GREEN: 0, YELLOW: 1, RED: 2 },
  };
  function compareField(
    field: string,
    a: Item,
    b: Item,
    desc: boolean,
  ): number {
    const av = getters[field](a);
    const bv = getters[field](b);
    if (av == null || bv == null) {
      if (av == null && bv == null) return 0;
      return av == null ? 1 : -1;
    }
    const rank = valueRank[field];
    let r: number;
    if (rank) {
      r = (rank[String(av)] ?? 9) - (rank[String(bv)] ?? 9);
    } else if (typeof av === "boolean" && typeof bv === "boolean") {
      r = Number(av) - Number(bv);
    } else if (field === "number") {
      // number 是"KEY-N"串,按尾数字自然排序(还原后端 int 序;YPJ-123)
      r = String(av).localeCompare(String(bv), undefined, { numeric: true });
    } else {
      r = String(av).localeCompare(String(bv));
    }
    return desc ? -r : r;
  }
  // 单击循环 升→降→移除;移除后空回默认;Shift+点击追加次级键(YPJ-124)
  function toggleSort(field: string, shift: boolean) {
    setSorts((prev) => {
      const idx = prev.findIndex((k) => k.field === field);
      if (idx === -1)
        return shift
          ? [...prev, { field, dir: "asc" }]
          : [{ field, dir: "asc" }];
      const { dir } = prev[idx];
      const rest = prev.filter((_, i) => i !== idx);
      if (dir === "asc") {
        const flipped: SortKey = { field, dir: "desc" };
        return shift ? [...rest, flipped] : [flipped];
      }
      return rest.length > 0 ? rest : DEFAULT_SORTS;
    });
  }

  const sorted = [...(items ?? [])]
    .filter(
      (it) =>
        (!filters.status || it.status === filters.status) &&
        (!filters.assignee ||
          (filters.assignee === "__none__"
            ? it.assignee == null
            : it.assignee === filters.assignee)) &&
        (!filters.priority || it.priority === filters.priority),
    )
    .sort((a, b) => {
      for (const { field, dir } of sorts) {
        const r = compareField(field, a, b, dir === "desc");
        if (r !== 0) return r;
      }
      return 0;
    });

  const selectedName =
    views.find((v) => v.viewId === selectedView)?.name ?? t.view.defaultView;

  return (
    <PageShell>
      <Stack
        direction="row"
        spacing={2}
        alignItems="center"
        sx={{ mb: 2 }}
        flexWrap="wrap"
        useFlexGap
      >
        <Typography variant="h5">{`${String(key)} · 表格`}</Typography>
        <Box sx={{ flex: 1 }} />
        {/* 视图控件(YPJ-47) */}
        <Dropdown
          ariaLabel={t.view.name}
          size="small"
          value={selectedView ?? ""}
          onChange={(v) => {
            const target = views.find((x) => x.viewId === v);
            if (target) applyView(target);
            else {
              setSelectedView(null);
              setFilters(EMPTY_FILTERS);
              setCols(readCols(key));
              setDirty(false);
            }
          }}
          sx={{ width: 180 }}
          options={[
            { value: "", label: t.view.defaultView },
            ...views.map((v) => ({ value: v.viewId, label: v.name })),
          ]}
        />
        <Button
          size="small"
          variant={dirty ? "contained" : "outlined"}
          onClick={() => {
            if (selectedView) void saveCurrent(selectedName);
            else {
              setViewName("");
              setNameOpen("save");
            }
          }}
        >
          {t.view.save}
          {dirty ? " ·" : ""}
        </Button>
        <Button
          size="small"
          onClick={() => {
            setViewName("");
            setNameOpen("saveAs");
          }}
        >
          {t.view.saveAs}
        </Button>
        {selectedView && (
          <Button size="small" color="error" onClick={() => void deleteView()}>
            {t.view.delete}
          </Button>
        )}
      </Stack>

      {/* 筛选 + 列配置(YPJ-47/28) */}
      <Stack
        direction="row"
        spacing={1}
        sx={{ mb: 1 }}
        flexWrap="wrap"
        useFlexGap
        alignItems="center"
      >
        <Dropdown
          ariaLabel={t.view.filterStatus}
          size="small"
          clearable
          value={filters.status}
          onChange={(v) => setFilter({ status: v })}
          sx={{ width: 130 }}
          placeholder={t.view.filterStatus}
          options={statuses.map((s) => ({ value: s.name, label: s.name }))}
        />
        <Dropdown
          ariaLabel={t.view.filterAssignee}
          size="small"
          clearable
          value={filters.assignee}
          onChange={(v) => setFilter({ assignee: v })}
          sx={{ width: 130 }}
          placeholder={t.view.filterAssignee}
          options={[...assignees, "__none__"].map((a) => ({
            value: a,
            label: a === "__none__" ? t.view.unassigned : a,
          }))}
        />
        <Dropdown
          ariaLabel={t.view.filterPriority}
          size="small"
          clearable
          value={filters.priority}
          onChange={(v) => setFilter({ priority: v })}
          sx={{ width: 130 }}
          placeholder={t.view.filterPriority}
          options={["P0", "P1", "P2", "P3"].map((p) => ({
            value: p,
            label: p,
          }))}
        />
        <ColumnConfigPopover
          columns={columns.filter((c) => c.id !== "number" && c.id !== "title")}
          visible={(id) =>
            cols[id] ?? columns.find((c) => c.id === id)?.visible ?? true
          }
          onToggle={toggleCol}
          onReset={() => {
            setCols({});
            setDirty(true);
          }}
        />
        {dirty && (
          <Typography variant="caption" color="warning.main">
            {t.view.dirty}
          </Typography>
        )}
      </Stack>

      {error && <Typography color="error">{error}</Typography>}
      {items !== null && items.length === 0 && (
        <Typography color="text.secondary">暂无 item。</Typography>
      )}
      {items !== null && items.length > 0 && (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                {visibleCols.map((c) => {
                  const idx = sorts.findIndex((k) => k.field === c.id);
                  return (
                    <TableCell
                      key={c.id}
                      sortDirection={idx >= 0 ? sorts[idx].dir : false}
                    >
                      <TableSortLabel
                        active={idx >= 0}
                        direction={idx >= 0 ? sorts[idx].dir : "asc"}
                        onClick={(e) => toggleSort(c.id, e.shiftKey)}
                        sx={{
                          // MUI 默认非激活列 hover 才见箭头,可发现性差;常显(半透明,激活实色)
                          ".MuiTableSortLabel-icon": { opacity: 0.3 },
                          "&:hover .MuiTableSortLabel-icon": { opacity: 0.6 },
                          "&.Mui-active .MuiTableSortLabel-icon": {
                            opacity: 1,
                          },
                        }}
                      >
                        {c.label}
                        {sorts.length > 1 && idx >= 0 && (
                          <Typography
                            component="span"
                            variant="caption"
                            sx={{ ml: 0.5, opacity: 0.6 }}
                          >
                            {idx + 1}
                          </Typography>
                        )}
                      </TableSortLabel>
                    </TableCell>
                  );
                })}
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map((it) => (
                <TableRow key={it.itemId} hover>
                  {visibleCols.map((c) => (
                    <TableCell key={c.id}>
                      {c.id === "number" ? (
                        <Link href={`/p/${key}/i/${it.itemId}?from=table`}>
                          <Typography
                            variant="body2"
                            sx={{ whiteSpace: "nowrap" }}
                          >
                            {(it.parentNumber ? it.parentNumber + " › " : "") +
                              it.number}
                          </Typography>
                        </Link>
                      ) : c.id === "title" ? (
                        <Link href={`/p/${key}/i/${it.itemId}?from=table`}>
                          <Typography variant="body2">{it.title}</Typography>
                        </Link>
                      ) : c.id === "status" ? (
                        it.status
                      ) : c.id === "priority" ? (
                        (it.priority ?? "")
                      ) : c.id === "assignee" ? (
                        <AssignPopover
                          itemId={it.itemId}
                          assignee={it.assignee}
                          onChanged={load}
                        >
                          {(onClick) => (
                            <Chip
                              size="small"
                              variant="outlined"
                              label={
                                it.assignee
                                  ? `👤 ${it.assignee}`
                                  : t.assign.none
                              }
                              onClick={onClick}
                              sx={it.assignee ? undefined : { opacity: 0.55 }}
                            />
                          )}
                        </AssignPopover>
                      ) : c.id === "startDate" ? (
                        (it.startDate ?? "")
                      ) : c.id === "dueDate" ? (
                        <span
                          style={{
                            color: it.overdue
                              ? "red"
                              : it.dueSoon
                                ? "orange"
                                : "inherit",
                          }}
                        >
                          {it.dueDate ?? ""}
                        </span>
                      ) : c.id === "overdue" ? (
                        it.overdue ? (
                          "是"
                        ) : (
                          "否"
                        )
                      ) : c.id === "feasSignal" ? (
                        it.feasSignal ? (
                          t.signal[it.feasSignal]
                        ) : (
                          ""
                        )
                      ) : c.id === "sprint" ? (
                        (it.sprintName ?? (
                          <Typography variant="body2" sx={{ opacity: 0.5 }}>
                            {t.view.backlog}
                          </Typography>
                        ))
                      ) : c.id === "blocked" ? (
                        it.blocked ? (
                          "⛔"
                        ) : (
                          ""
                        )
                      ) : (
                        ""
                      )}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* 命名 Dialog(save 首存 / saveAs 另存共用) */}
      <Dialog open={nameOpen !== null} onClose={() => setNameOpen(null)}>
        <DialogTitle>
          {nameOpen === "saveAs" ? t.view.saveAs : t.view.save}
        </DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            size="small"
            label={t.view.viewName}
            value={viewName}
            onChange={(e) => setViewName(e.target.value)}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNameOpen(null)}>{t.assign.cancel}</Button>
          <Button
            variant="contained"
            disabled={!viewName.trim()}
            onClick={() => void saveCurrent(viewName.trim())}
          >
            {t.view.save}
          </Button>
        </DialogActions>
      </Dialog>
    </PageShell>
  );
}
