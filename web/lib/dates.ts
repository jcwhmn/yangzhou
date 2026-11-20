// YPJ-208(spec 0138 切片 c):item 起止升到 datetime 后的展示裁剪——列表/卡片只显 yyyy-MM-dd。
export function fmtDate(iso: string | null | undefined): string {
  return (iso ?? "").slice(0, 10);
}

// YPJ-218(spec 0139 B2):甘特日期解析统一本地墙钟——date-only 补 T00:00 再 new Date,
// 否则 date-only 走 UTC 零点、datetime 走本地时区,两者差 8 小时(刻度/条位/今日线错位)。
export function parseLocal(iso: string): Date {
  return new Date(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? `${iso}T00:00` : iso);
}

// YPJ-244:甘特 hover 卡展示时分(数据含时分,不再裁成 date-only);date-only 入参回退为 00:00
export function fmtDateTime(iso: string): string {
  const d = parseLocal(iso);
  if (isNaN(+d)) return iso;
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
