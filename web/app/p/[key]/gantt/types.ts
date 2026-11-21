// 甘特页共享类型与常量(YPJ-249 自 page.tsx 原样搬出,零语义变化)

export type GanttRow = {
  itemId: string;
  parentItemId: string | null;
  number: string;
  title: string;
  startDate: string | null;
  dueDate: string | null;
  statusName: string;
  statusType: string | null;
  overdue: boolean;
  blocked: boolean;
  estimateValue: number | null;
  estimateUnit: string | null;
  depth: number;
  actualStart: string | null;
  actualEnd: string | null;
  anchorDate: string | null; // YPJ-244(spec 0151):依赖锚点=多前置最晚 due(无 due 退 start);null=无可用前置时间
  assigneeName: string | null;
  checklistDone: number | null;
  checklistTotal: number | null;
};

export const DAY = 24 * 60 * 60 * 1000;

// YPJ-219 v2(spec 0140):轴 = 数据锚定(首日 00:00 → 末日 24:00),连续缩放,无语义档位
export const TITLE_W_DEFAULT = 280; // 标题列默认宽(=236 出厂值;237 双击重置回落此值)
export const TITLE_W_MIN = 120;
export const TITLE_W_LS = "yz-gantt-title-w"; // 237:标题列宽 localStorage 记忆键
export const MAX_PPD = 2400; // 缩放上限:1日窗承诺在内容宽 ≤2680 成立(YPJ-230;1920 屏内容 1386 原被 720 夹成 ~1.9 天)
export const GANTT_MIN_PPD = 3; // 缺省视窗 pxPerDay 下限(YPJ-234 拍板案 B 修订:宽区间不把条压没;1 天 ≈ 3px、1 月 ≈ 91px 可读;只约束缺省态,显式缩放仍可压破至 fit)
export const MIN_LABEL_PX = 44; // 刻度标签最小间距
export const ZOOM_STEP = 1.5; // ± 按钮步进系数

export type Tick = { x: number; label: string; key: string };
export type HeaderRow = {
  gran: "month" | "week" | "day" | "hour";
  ticks: Tick[];
};

// YPJ-221(spec 0158 r2):milestone/release 列表 DTO 前端窄型(泳道/竖线旗标用;列表 API 返回超集,只声明所需字段)
export type MilestoneMarker = {
  milestoneId: string;
  name: string;
  status: string; // planned/in_progress/completed/cancelled
  targetDate: string | null;
  overdue: boolean;
  gateDone: number | null; // 双 null = 零 gate 站
  gateTotal: number | null;
  daysToTarget: number | null; // 负=已超
};
export type ReleaseMarker = {
  releaseId: string;
  name: string;
  version: string | null;
  stage: string | null;
  status: string; // planned/released
  targetDate: string | null;
  releasedDate: string | null;
  overdue: boolean;
};

// estimateUnit 封闭枚举(hour/day/week)的短标(YPJ-204)
export const EST_UNIT_SHORT: Record<string, string> = {
  hour: "h",
  day: "d",
  week: "w",
};
