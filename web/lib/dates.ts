// YPJ-208(spec 0138 切片 c):item 起止升到 datetime 后的展示裁剪——列表/卡片只显 yyyy-MM-dd。
export function fmtDate(iso: string | null | undefined): string {
  return (iso ?? "").slice(0, 10);
}
