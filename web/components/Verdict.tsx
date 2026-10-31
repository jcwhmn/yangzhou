// 判定行/信号 chip:颜色语义同 DOMAIN.md(有余克制灰、未评级紫、缺门红、差级琥珀)
import { Chip } from "@mui/material";
import { t } from "@/lib/texts";
import { tokens } from "@/lib/tokens";

export type Verdict = {
  kind: "satisfied" | "surplus" | "gap" | "unrated" | "missing";
  attribute: string;
  delta?: number | null;
  required?: number | null;
  actual?: number | null;
};

// 色值取自 tokens(V65 收敛单一真相源);仍以字符串导出:borderLeft 拼接等非 sx 场景需要裸值
const colors: Record<Verdict["kind"], string> = {
  satisfied: tokens.success,
  surplus: tokens.neutral, // 有余:克制,不炫耀
  gap: tokens.gap,
  unrated: tokens.accent,
  missing: tokens.error,
};

export function verdictText(v: Verdict): string {
  switch (v.kind) {
    case "satisfied":
      return t.verdicts.satisfied(v.attribute);
    case "surplus":
      return t.verdicts.surplus(v.attribute);
    case "gap":
      return t.verdicts.gap(
        v.attribute,
        v.delta ?? 0,
        v.required ?? 0,
        v.actual ?? 0,
      );
    case "unrated":
      return t.verdicts.unrated(v.attribute, v.required ?? 0);
    case "missing":
      return t.verdicts.missing(v.attribute);
  }
}

export function VerdictLine({ v }: { v: Verdict }) {
  return (
    <div style={{ color: colors[v.kind], fontSize: 14, lineHeight: 1.9 }}>
      {verdictText(v)}
    </div>
  );
}

export type Signal = "GREEN" | "YELLOW" | "RED";

export const signalColor: Record<Signal, "success" | "warning" | "error"> = {
  GREEN: "success",
  YELLOW: "warning",
  RED: "error",
};

export function SignalChip({
  signal,
  size = "medium",
}: {
  signal: Signal;
  size?: "small" | "medium";
}) {
  return (
    <Chip
      size={size as "small"}
      label={t.signal[signal]}
      color={signalColor[signal]}
      variant="outlined"
    />
  );
}

/** 候选行配色(YPJ-1):全满足绿 / 勉强(delta=1)与有差距同橙 / 部分满足琥珀 / 缺门红(V65 三琥珀归两档) */
export function candidateColor(c: {
  signal: Signal;
  totalDelta: number;
}): string {
  if (c.signal === "RED") return tokens.error;
  if (c.signal === "GREEN") return tokens.success;
  return c.totalDelta === 1 ? tokens.warning : tokens.gap;
}
