// YPJ-275(spec 0167):关闭理由枚举与弹窗判定——枚举名与后端 CHECK 白名单逐字一致。
// shouldAsk 用结构化最小类型:board 与详情页各有 Status 定义,只约束用到的字段。

export const CLOSE_REASONS = [
  "done",
  "wontfix",
  "expired",
  "duplicate",
  "superseded",
] as const;
export type CloseReason = (typeof CLOSE_REASONS)[number];

/** 源状态要求理由 ∧ 目标是终态 → 前端须弹窗(spec 0167 §五;false 时走现状零打扰,后端写 done) */
export function shouldAsk(
  statuses: { requireCloseReason: boolean; name: string }[],
  fromName: string,
  toStatus: { statusType: string },
): boolean {
  const from = statuses.find((s) => s.name === fromName);
  return !!from?.requireCloseReason && toStatus.statusType === "FINAL";
}
