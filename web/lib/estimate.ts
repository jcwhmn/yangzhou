import { t } from "./texts";

// YPJ-186(spec 0128):预估快照(value+unit)前端助手。
// 换算常数用户拍板固定 1day=8h、1week=40h(拍板点②),当前唯一消费是表格排序;
// 将来要项目级配置化只改本文件。

export type EstimateScale = {
  key: string;
  label: string;
  value: number;
  unit: string;
};

/** 显示文本:数值去尾零 + 单位中文(「5天」);空值返回空串(表格/卡片都不占位)。 */
export function estimateText(
  value?: number | null,
  unit?: string | null,
): string {
  if (value == null || !unit) return "";
  return `${value}${t.estimate.units[unit] ?? unit}`;
}

/** 规范化小时(表格排序 key;空值返回 null,由排序层统一排最后)。 */
export function estimateHours(
  value?: number | null,
  unit?: string | null,
): number | null {
  if (value == null || !unit) return null;
  const per = unit === "hour" ? 1 : unit === "day" ? 8 : 40;
  return value * per;
}
