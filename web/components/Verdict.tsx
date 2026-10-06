// 判定行/信号 chip:颜色语义同 DOMAIN.md(有余克制灰、未评级紫、缺门红、差级琥珀)
import { Chip } from "@mui/material";
import { t } from "@/lib/texts";

export type Verdict = {
  kind: "satisfied" | "surplus" | "gap" | "unrated" | "missing";
  attribute: string;
  delta?: number | null;
  required?: number | null;
  actual?: number | null;
};

const colors: Record<Verdict["kind"], string> = {
  satisfied: "#2e7d32",
  surplus: "#9e9e9e", // 有余:克制,不炫耀
  gap: "#b45309",
  unrated: "#7c4dff",
  missing: "#c62828",
};

export function verdictText(v: Verdict): string {
  switch (v.kind) {
    case "satisfied":
      return t.verdicts.satisfied(v.attribute);
    case "surplus":
      return t.verdicts.surplus(v.attribute);
    case "gap":
      return t.verdicts.gap(v.attribute, v.delta ?? 0, v.required ?? 0, v.actual ?? 0);
    case "unrated":
      return t.verdicts.unrated(v.attribute, v.required ?? 0);
    case "missing":
      return t.verdicts.missing(v.attribute);
  }
}

export function VerdictLine({ v }: { v: Verdict }) {
  return (
    <div style={{ color: colors[v.kind], fontSize: 14, lineHeight: 1.9 }}>{verdictText(v)}</div>
  );
}

export type Signal = "GREEN" | "YELLOW" | "RED";

export const signalColor: Record<Signal, "success" | "warning" | "error"> = {
  GREEN: "success",
  YELLOW: "warning",
  RED: "error",
};

export function SignalChip({ signal, size = "medium" }: { signal: Signal; size?: "small" | "medium" }) {
  return <Chip size={size as "small"} label={t.signal[signal]} color={signalColor[signal]} variant="outlined" />;
}

/** 候选行配色(YPJ-1):全满足绿 / 勉强(delta=1)浅橙 / 部分满足琥珀 / 缺门红。
 *  字面 hex 而非主题路径:调用处(borderLeft 字符串拼接/sx borderColor)两种场景都能直接用,色值对齐上方 colors 色板 */
export function candidateColor(c: { signal: Signal; totalDelta: number }): string {
  if (c.signal === "RED") return "#c62828";
  if (c.signal === "GREEN") return "#2e7d32";
  return c.totalDelta === 1 ? "#ff9800" : "#ed6c02";
}
