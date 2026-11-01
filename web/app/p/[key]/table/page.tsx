"use client";

import {
  Box,
  LinearProgress,
  Button,
  Chip,
  InputAdornment,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TablePagination,
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
import SearchIcon from "@mui/icons-material/Search";

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
  attributes: { name: string; value: string | null }[];
};
type ViewDto = {
  viewId: string;
  name: string;
  filters: Record<string, string | null>;
  columns: Record<string, boolean>;
  colOrder?: string[] | null;
  sort?: { field: string; dir: "asc" | "desc" }[] | null;
  query?: string | null;
};
type Filters = { status: string; assignee: string; priority: string };

const EMPTY_FILTERS: Filters = { status: "", assignee: "", priority: "" };

// 列定义真相源(spec 0091 §6):编号恒显钉首位、不入顺序数组,其余按此默认顺序
type ColumnDef = { id: string; label: string; visible: boolean };
const COLUMN_DEFS: ColumnDef[] = [
  { id: "number", label: "编号", visible: true },
  { id: "title", label: "标题", visible: true },
  { id: "priority", label: "优先级", visible: false },
  { id: "status", label: "状态", visible: true },
  { id: "assignee", label: "负责人", visible: true },
  { id: "startDate", label: "开始", visible: true },
  { id: "dueDate", label: "截止", visible: true },
  { id: "overdue", label: "超期", visible: true },
  { id: "feasSignal", label: "可行性", visible: true },
  { id: "blocked", label: "阻塞", visible: false },
  { id: "sprint", label: "Sprint", visible: true },
];
const MOVABLE_IDS = COLUMN_DEFS.slice(1).map((c) => c.id);
const DEFAULT_COL_ORDER = MOVABLE_IDS;

function normalizeOrder(saved?: string[] | null): string[] {
  const known = new Set(MOVABLE_IDS);
  const ordered = (saved ?? []).filter((id) => known.has(id));
  const rest = MOVABLE_IDS.filter((id) => !ordered.includes(id));
  return [...ordered, ...rest]; // 存档剔除未知 id,新增列按默认顺序补尾
}

function readSorts(k: string): SortKey[] {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-sorts:${k}`) ?? "null");
    return Array.isArray(v) && v.length > 0 ? v : DEFAULT_SORTS;
  } catch {
    return DEFAULT_SORTS;
  }
}

function readQuery(k: string): string {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-query:${k}`) ?? "null");
    return typeof v === "string" ? v : "";
  } catch {
    return "";
  }
}

function readSelectedView(k: string): string | null {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-view:${k}`) ?? "null");
    return typeof v === "string" ? v : null;
  } catch {
    return null;
  }
}

type SortKey = { field: string; dir: "asc" | "desc" };

const PAGE_SIZES = [20, 50, 100];

function readPageSize(): number {
  if (typeof window === "undefined") return PAGE_SIZES[0];
  const n = Number(localStorage.getItem("yz-table-page-size"));
  return PAGE_SIZES.includes(n) ? n : PAGE_SIZES[0];
}

const DEFAULT_SORTS: SortKey[] = [{ field: "number", dir: "asc" }];

/** YPJ-126 表内搜索:不区分大小写子串匹配可见字段(不含 description,YPJ-122)。 */
function matches(it: Item, q: string): boolean {
  if (!q) return true;
  const s = q.toLowerCase();
  return (
    it.number.toLowerCase().includes(s) ||
    (it.parentNumber ?? "").toLowerCase().includes(s) ||
    it.title.toLowerCase().includes(s) ||
    (it.assignee ?? "").toLowerCase().includes(s) ||
    (it.sprintName ?? "").toLowerCase().includes(s) ||
    (it.attributes ?? []).some((a) => (a.value ?? "").toLowerCase().includes(s))
  );
}

/** V10-S4 表格视图;YPJ-47 视图配置(spec 0045):筛选(客户端)+ 视图保存/切换 + 列配置 Popover(YPJ-28 合并)。 */
export default function TableViewPage() {
  const { key } = useParams<{ key: string }>();
  const [items, setItems] = useState<Item[] | null>(null);
  const [statuses, setStatuses] = useState<Status[]>([]);
  const [sorts, setSorts] = useState<SortKey[]>(() => readSorts(key));
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(readPageSize);
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [query, setQuery] = useState(() => readQuery(key));
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

  function readOrder(k: string): string[] {
    try {
      return normalizeOrder(
        JSON.parse(localStorage.getItem(`yz-table-colorder:${k}`) ?? "[]"),
      );
    } catch {
      return DEFAULT_COL_ORDER;
    }
  }

  const [colOrder, setColOrder] = useState<string[]>(() => readOrder(key));

  // 视图状态
  const [views, setViews] = useState<ViewDto[]>([]);
  const [selectedView, setSelectedView] = useState<string | null>(() =>
    readSelectedView(key),
  );
  const [dirty, setDirty] = useState(false);
  const [nameOpen, setNameOpen] = useState<null | "save" | "saveAs">(null);
  const [viewName, setViewName] = useState("");
  const [saving, setSaving] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [fatal, setFatal] = useState(false);

  useEffect(() => {
    setCols(readCols(key));
    setColOrder(readOrder(key));
    setFilters(EMPTY_FILTERS);
    setQuery(readQuery(key));
    setSorts(readSorts(key));
    setSelectedView(readSelectedView(key));
    setLoaded(false);
    setFatal(false);
    setDirty(false);
  }, [key]);

  // 仅默认视图写 localStorage;选中视图时写会把默认视图存档冲掉(YPJ-131 实测)
  useEffect(() => {
    if (selectedView === null) {
      localStorage.setItem(`yz-table-cols:${key}`, JSON.stringify(cols));
    }
  }, [cols, key, selectedView]);

  useEffect(() => {
    if (selectedView === null) {
      localStorage.setItem(
        `yz-table-colorder:${key}`,
        JSON.stringify(colOrder),
      );
    }
  }, [colOrder, key, selectedView]);

  useEffect(() => {
    if (selectedView === null) {
      localStorage.setItem(`yz-table-sorts:${key}`, JSON.stringify(sorts));
      localStorage.setItem(`yz-table-query:${key}`, JSON.stringify(query));
    }
  }, [sorts, query, key, selectedView]);

  // 记住上次选中的视图,重进自动应用(YPJ-131 手测反馈)
  useEffect(() => {
    localStorage.setItem(`yz-table-view:${key}`, JSON.stringify(selectedView));
  }, [selectedView, key]);

  // 错误条 6s 自动消失;命名对话框开着时保持(YPJ-131 手测反馈:409 信息一直不消失)
  useEffect(() => {
    if (!error || nameOpen !== null || fatal) return;
    const timer = setTimeout(() => setError(""), 6000);
    return () => clearTimeout(timer);
  }, [error, nameOpen, fatal]);

  const load = useCallback(async () => {
    setError("");
    setFatal(false);
    try {
      const [its, sts, vs] = await Promise.all([
        api<Item[]>(`/api/projects/${key}/items`),
        api<{ statuses: Status[] }>(`/api/projects/${key}`).then(
          (p) => p.statuses,
        ),
        api<ViewDto[]>(`/api/projects/${key}/views`),
      ]);
      setItems(its);
      setStatuses(sts);
      setViews(vs);
      setLoaded(true);

      // 记住上次选中的视图,重进自动应用(YPJ-131 手测反馈)
      const remembered = readSelectedView(key);
      const target = remembered
        ? vs.find((v) => v.viewId === remembered)
        : undefined;
      if (target) applyView(target);
      else setSelectedView(null);
    } catch (e) {
      // 加载失败=死页面:锁操作+常驻错误+重试(YPJ-131 复验:token 过期 401 后
      // views=[] 令同名预检失效,默认视图下保存误走 POST 撞同名 409)
      setLoaded(false);
      setFatal(true);
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

  function moveCol(id: string, dir: "up" | "down") {
    setColOrder((prev) => {
      const i = prev.indexOf(id);
      const j = dir === "up" ? i - 1 : i + 1;
      if (i < 0 || j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
    setDirty(true);
  }

  function setFilter(f: Partial<Filters>) {
    setFilters((prev) => ({ ...prev, ...f }));
    setPage(0);
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
    setColOrder(normalizeOrder(v.colOrder));
    setSorts(v.sort ?? DEFAULT_SORTS);
    setQuery(v.query ?? "");
    setDirty(false);
  }

  async function saveCurrent(name: string) {
    // 防连点:双击会 POST 两次,第二次 409 同名(YPJ-131 手测反馈)
    if (saving) return;
    setSaving(true);
    const body = {
      name,
      filters: {
        status: filters.status || null,
        assignee: filters.assignee || null,
        priority: filters.priority || null,
      },
      columns: cols,
      colOrder,
      sort: sorts,
      query: query,
    };
    try {
      // 选中视图的工具栏直存=PATCH(不弹框,nameOpen=null);另存=POST 建副本。
      // 旧条件 nameOpen === "save" 永假 → 直存误走 POST 撞同名 409,顺序/排序不落库(YPJ-127 起遗留,复验实锤)
      if (selectedView && nameOpen !== "saveAs") {
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
    } finally {
      setSaving(false);
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
      setColOrder(readOrder(key));
      setSorts(readSorts(key));
      setQuery(readQuery(key));
      setError("");
      setDirty(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    }
  }

  const columns: ColumnDef[] = COLUMN_DEFS.map((c) => ({
    ...c,
    visible: c.id === "number" || c.id === "title" || (cols[c.id] ?? c.visible),
  }));

  const visibleCols = ["number", ...colOrder]
    .map((id) => columns.find((c) => c.id === id))
    .filter((c): c is ColumnDef => c !== undefined && c.visible);

  // 另存/首存同名预检:后端 409 前置拦截(YPJ-131 手测反馈)
  const nameConflict =
    nameOpen !== null &&
    viewName.trim().length > 0 &&
    views.some(
      (v) =>
        v.name === viewName.trim() &&
        !(nameOpen === "save" && v.viewId === selectedView),
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
  // 多列排序:普通点击=追加/翻向/移除;Shift+点击=仅按此列(替换)(YPJ-131 用户拍板)
  function toggleSort(field: string, replace: boolean) {
    setPage(0);
    setDirty(true);
    setSorts((prev) => {
      const idx = prev.findIndex((k) => k.field === field);
      if (idx === -1)
        return replace
          ? [{ field, dir: "asc" }]
          : [...prev, { field, dir: "asc" }];
      const { dir } = prev[idx];
      const rest = prev.filter((_, i) => i !== idx);
      if (dir === "asc") {
        return [...rest, { field, dir: "desc" }];
      }
      return rest.length > 0 ? rest : DEFAULT_SORTS;
    });
  }

  const sorted = [...(items ?? [])]
    .filter(
      (it) =>
        matches(it, query) &&
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

  // 分页切片;页码越界(筛选后行数变少)兜底(YPJ-125)
  const safePage = Math.min(
    page,
    Math.max(0, Math.ceil(sorted.length / rowsPerPage) - 1),
  );
  const paged = sorted.slice(
    safePage * rowsPerPage,
    (safePage + 1) * rowsPerPage,
  );

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
              setColOrder(readOrder(key));
              setSorts(readSorts(key));
              setQuery(readQuery(key));
              setError("");
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
          disabled={!loaded}
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
          disabled={!loaded}
        >
          {t.view.saveAs}
        </Button>
        {selectedView && (
          <Button
            size="small"
            color="error"
            disabled={!loaded}
            onClick={() => void deleteView()}
          >
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
        <TextField
          aria-label={t.view.searchPlaceholder}
          placeholder={t.view.searchPlaceholder}
          size="small"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setPage(0);
            setDirty(true);
          }}
          sx={{ width: 220 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
        />
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
          disabled={!loaded}
          columns={colOrder.flatMap((id) => {
            const c = columns.find((x) => x.id === id);
            return c ? [{ ...c, locked: id === "title" }] : [];
          })}
          visible={(id) =>
            cols[id] ?? columns.find((c) => c.id === id)?.visible ?? true
          }
          onToggle={toggleCol}
          onMove={moveCol}
          onReset={() => {
            setCols({});
            setColOrder(DEFAULT_COL_ORDER);
            setDirty(true);
          }}
        />
        {dirty && (
          <Typography variant="caption" color="warning.main">
            {t.view.dirty}
          </Typography>
        )}
      </Stack>

      {items === null && !error && <LinearProgress />}
      {error && (
        <Stack direction="row" spacing={1} alignItems="center">
          <Typography color="error">{error}</Typography>
          {fatal && (
            <Button size="small" variant="outlined" onClick={() => void load()}>
              {t.view.retry}
            </Button>
          )}
        </Stack>
      )}
      {items !== null && items.length === 0 && (
        <Typography color="text.secondary">暂无 item。</Typography>
      )}
      {items !== null && items.length > 0 && (
        <>
          <TableContainer sx={{ maxHeight: "calc(100vh - 280px)" }}>
            <Table size="small" stickyHeader>
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
                          title={t.view.sortHint}
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
                {paged.map((it) => (
                  <TableRow key={it.itemId} hover>
                    {visibleCols.map((c) => (
                      <TableCell key={c.id}>
                        {c.id === "number" ? (
                          <Link href={`/p/${key}/i/${it.itemId}?from=table`}>
                            <Typography
                              variant="body2"
                              sx={{ whiteSpace: "nowrap" }}
                            >
                              {(it.parentNumber
                                ? it.parentNumber + " › "
                                : "") + it.number}
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
          <TablePagination
            rowsPerPageOptions={PAGE_SIZES}
            count={sorted.length}
            page={safePage}
            onPageChange={(_, p) => setPage(p)}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={(e) => {
              setRowsPerPage(Number(e.target.value));
              setPage(0);
              localStorage.setItem("yz-table-page-size", e.target.value);
            }}
            labelRowsPerPage={t.view.perPage}
            labelDisplayedRows={({ from, to, count }) =>
              `${from}-${to} / ${count}`
            }
          />
        </>
      )}

      {/* 命名 Dialog(save 首存 / saveAs 另存共用) */}
      <Dialog open={nameOpen !== null} onClose={() => setNameOpen(null)}>
        <DialogTitle>
          {nameOpen === "saveAs" ? t.view.saveAs : t.view.save}
        </DialogTitle>
        <DialogContent>
          {nameOpen !== null && error && (
            <Typography color="error" variant="body2">
              {error}
            </Typography>
          )}
          <TextField
            autoFocus
            fullWidth
            size="small"
            label={t.view.viewName}
            value={viewName}
            error={nameConflict}
            helperText={nameConflict ? t.view.nameConflict : undefined}
            onChange={(e) => setViewName(e.target.value)}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNameOpen(null)}>{t.assign.cancel}</Button>
          <Button
            variant="contained"
            disabled={!viewName.trim() || nameConflict || saving}
            onClick={() => void saveCurrent(viewName.trim())}
          >
            {t.view.save}
          </Button>
        </DialogActions>
      </Dialog>
    </PageShell>
  );
}
