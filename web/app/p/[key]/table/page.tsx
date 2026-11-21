"use client";

import { Button, LinearProgress, Stack, Typography } from "@mui/material";
import { PageShell } from "@/components/PageShell";
import TableFilterBar from "./components/TableFilterBar";
import TableToolbar from "./components/TableToolbar";
import ViewNameDialog from "./components/ViewNameDialog";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import {
  compareField,
  COLUMN_DEFS,
  DEFAULT_COL_ORDER,
  DEFAULT_SORTS,
  EMPTY_FILTERS,
  matches,
  normalizeOrder,
  PAGE_SIZES,
  readPageSize,
  readQuery,
  readSelectedView,
  readSorts,
  type ColumnDef,
  type Filters,
  type Item,
  type SortKey,
  type Status,
  type ViewDto,
} from "./shared";
import ItemsTable from "./components/ItemsTable";

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

  // 多栏排序:sorts 顺序即优先级;null 恒排最后,不随方向翻转(YPJ-124)——机器在 shared
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
      <TableToolbar
        projectKey={key}
        selectedView={selectedView}
        views={views}
        selectedName={selectedName}
        dirty={dirty}
        loaded={loaded}
        onViewChange={(v) => {
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
        onSave={() => {
          if (selectedView) void saveCurrent(selectedName);
          else {
            setViewName("");
            setNameOpen("save");
          }
        }}
        onSaveAs={() => {
          setViewName("");
          setNameOpen("saveAs");
        }}
        onDelete={() => void deleteView()}
      />

      <TableFilterBar
        query={query}
        onQueryChange={(v) => {
          setQuery(v);
          setPage(0);
          setDirty(true);
        }}
        filters={filters}
        onFilterChange={setFilter}
        statuses={statuses}
        assignees={assignees}
        columns={columns}
        colOrder={colOrder}
        cols={cols}
        dirty={dirty}
        loaded={loaded}
        onToggleCol={toggleCol}
        onMoveCol={moveCol}
        onResetCols={() => {
          setCols({});
          setColOrder(DEFAULT_COL_ORDER);
          setDirty(true);
        }}
      />

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
          <ItemsTable
            visibleCols={visibleCols}
            paged={paged}
            sorts={sorts}
            onToggleSort={toggleSort}
            projectKey={key}
            onAssigneeChanged={load}
            count={sorted.length}
            page={safePage}
            onPageChange={setPage}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={(e) => {
              setRowsPerPage(Number(e.target.value));
              setPage(0);
              localStorage.setItem("yz-table-page-size", e.target.value);
            }}
          />
        </>
      )}

      <ViewNameDialog
        open={nameOpen !== null}
        mode={nameOpen === "saveAs" ? "saveAs" : "save"}
        viewName={viewName}
        onNameChange={setViewName}
        nameConflict={nameConflict}
        saving={saving}
        error={error}
        onClose={() => setNameOpen(null)}
        onSave={() => void saveCurrent(viewName.trim())}
      />
    </PageShell>
  );
}
