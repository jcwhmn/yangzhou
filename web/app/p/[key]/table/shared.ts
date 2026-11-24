import { estimateHours } from "@/lib/estimate";

export type Status = { statusId: string; name: string; position: number };
export type Item = {
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
  estimateValue: number | null; // YPJ-186 预估快照
  estimateUnit: string | null;
  blocked: boolean;
  sprintName: string | null;
  closeReason: string | null; // YPJ-275:终态票恒非空(后端回填 done);非终态 null
  totalMinutes: number; // YPJ-182 时间合计(分钟),列表响应才带
  attributes: { name: string; value: string | null }[];
};
export type ViewDto = {
  viewId: string;
  name: string;
  filters: Record<string, string | null>;
  columns: Record<string, boolean>;
  colOrder?: string[] | null;
  sort?: { field: string; dir: "asc" | "desc" }[] | null;
  query?: string | null;
};
export type Filters = { status: string; assignee: string; priority: string };

export const EMPTY_FILTERS: Filters = {
  status: "",
  assignee: "",
  priority: "",
};

// 列定义真相源(spec 0091 §6):编号恒显钉首位、不入顺序数组,其余按此默认顺序
export type ColumnDef = { id: string; label: string; visible: boolean };
export const COLUMN_DEFS: ColumnDef[] = [
  { id: "number", label: "编号", visible: true },
  { id: "title", label: "标题", visible: true },
  { id: "priority", label: "优先级", visible: false },
  { id: "estimate", label: "预估", visible: true },
  { id: "timesheet", label: "工时", visible: false },
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
export const DEFAULT_COL_ORDER = MOVABLE_IDS;

export function normalizeOrder(saved?: string[] | null): string[] {
  const known = new Set(MOVABLE_IDS);
  const ordered = (saved ?? []).filter((id) => known.has(id));
  const rest = MOVABLE_IDS.filter((id) => !ordered.includes(id));
  return [...ordered, ...rest]; // 存档剔除未知 id,新增列按默认顺序补尾
}

export function readSorts(k: string): SortKey[] {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-sorts:${k}`) ?? "null");
    return Array.isArray(v) && v.length > 0 ? v : DEFAULT_SORTS;
  } catch {
    return DEFAULT_SORTS;
  }
}

export function readQuery(k: string): string {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-query:${k}`) ?? "null");
    return typeof v === "string" ? v : "";
  } catch {
    return "";
  }
}

export function readSelectedView(k: string): string | null {
  try {
    const v = JSON.parse(localStorage.getItem(`yz-table-view:${k}`) ?? "null");
    return typeof v === "string" ? v : null;
  } catch {
    return null;
  }
}

export type SortKey = { field: string; dir: "asc" | "desc" };

export const PAGE_SIZES = [20, 50, 100];

export function readPageSize(): number {
  if (typeof window === "undefined") return PAGE_SIZES[0];
  const n = Number(localStorage.getItem("yz-table-page-size"));
  return PAGE_SIZES.includes(n) ? n : PAGE_SIZES[0];
}

export const DEFAULT_SORTS: SortKey[] = [{ field: "number", dir: "asc" }];

/** YPJ-126 表内搜索:不区分大小写子串匹配可见字段(不含 description,YPJ-122)。 */
export function matches(it: Item, q: string): boolean {
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

/** 多栏排序机器(YPJ-124):sorts 顺序即优先级;null 恒排最后,不随方向翻转。 */
type Val = string | number | boolean;
const getters: Record<string, (i: Item) => Val | null> = {
  number: (i) => i.number,
  title: (i) => i.title,
  priority: (i) => i.priority,
  estimate: (i) => estimateHours(i.estimateValue, i.estimateUnit),
  timesheet: (i) => (i.totalMinutes > 0 ? i.totalMinutes : null),
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
export function compareField(
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
