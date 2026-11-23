// 行底色纯函数(spec 0165 SRS §3.2):StatusType→底色;ACTIVE 浓淡档数 = ACTIVE 状态数 n(用户 2026-11-26 拍板:不固定 3)
export const TINT = {
  START: "#EEF2F6", // 灰蓝:冷/未动
  FINAL: "#E7F5EC", // 淡绿:呼应绿=完成
  // 琥珀 5 阶浅→深(n>5 时相邻状态可能同色,异常多状态场景接受,SRS §3.2)
  ACTIVE: ["#FFF8E1", "#FFF2CD", "#FFEDB8", "#FFE7A3", "#FFE28E"],
};

/** 项目 ACTIVE 状态(按 position 升序)→ 状态名 → 档序号 0..n-1 */
export function activeTierMap(
  activeStatuses: { name: string; position: number }[],
): Map<string, number> {
  const sorted = [...activeStatuses].sort((a, b) => a.position - b.position);
  return new Map(sorted.map((s, i) => [s.name, i] as const));
}

/** 行底色;父行/非 item 状态行/statusType null → null(透明) */
export function statusTint(
  statusType: string | null,
  tier: number | null,
  activeCount: number,
): string | null {
  if (statusType === "START") return TINT.START;
  if (statusType === "FINAL") return TINT.FINAL;
  if (statusType === "ACTIVE" && tier !== null && activeCount > 0)
    return TINT.ACTIVE[
      Math.min(
        Math.floor((tier * TINT.ACTIVE.length) / activeCount),
        TINT.ACTIVE.length - 1,
      )
    ];
  return null;
}
