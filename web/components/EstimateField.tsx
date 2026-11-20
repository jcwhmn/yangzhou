"use client";

import { Button, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { t } from "@/lib/texts";
import { EstimateScale, estimateText } from "@/lib/estimate";

/**
 * YPJ-186(spec 0128):预估输入——档位下拉+自定义。
 * 表单态约定:value/unit 成对存字符串,双双空串=未填(交后端三态);选档=写该档 value+unit 快照。
 * 自定义值非法(空/≤0)在 value 输入框红框标示,由父级禁保存;required 项目隐藏清除按钮。
 */
export function EstimateField({
  scales,
  required,
  value,
  unit,
  onChange,
  size = "small",
}: {
  scales: EstimateScale[];
  required: boolean;
  value: string; // "" = 未填
  unit: string; // "" = 未填
  onChange: (value: string, unit: string) => void;
  size?: "small" | "medium";
}) {
  // YPJ-244(spec 0151):unit 非空也算自定义态——未填直接选「自定义」时数值框才出现(原判定 value!=="" 导致无档位项目无法录预估,台账 A31)
  const custom =
    (value !== "" || unit !== "") &&
    !scales.some((s) => String(s.value) === value && s.unit === unit);
  const selectValue =
    value === "" ? "" : custom ? "__custom" : `${value}|${unit}`;
  const invalid = custom && (value === "" || Number(value) <= 0);

  function pick(v: string) {
    if (v === "") return onChange("", "");
    if (v === "__custom") return onChange(value || "", unit || "hour");
    const hit = scales.find((s) => `${s.value}|${s.unit}` === v);
    if (hit) return onChange(String(hit.value), hit.unit);
  }

  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <TextField
        select
        label={t.estimate.label}
        size={size}
        value={selectValue}
        onChange={(e) => pick(e.target.value)}
        sx={{ minWidth: 150 }}
      >
        <MenuItem value="" disabled={required}>
          —
        </MenuItem>
        {scales.map((s) => (
          <MenuItem key={s.key} value={`${s.value}|${s.unit}`}>
            {`${s.label}(${estimateText(s.value, s.unit)})`}
          </MenuItem>
        ))}
        <MenuItem value="__custom">{t.estimate.custom}</MenuItem>
      </TextField>
      {custom && (
        <>
          <TextField
            label={t.estimate.valueLabel}
            size={size}
            type="number"
            value={value}
            error={invalid}
            onChange={(e) => onChange(e.target.value, unit)}
            sx={{ width: 90 }}
          />
          <TextField
            select
            label={t.estimate.unitLabel}
            size={size}
            value={unit || "hour"}
            onChange={(e) => onChange(value, e.target.value)}
            sx={{ width: 90 }}
          >
            {Object.entries(t.estimate.units).map(([k, v]) => (
              <MenuItem key={k} value={k}>
                {v}
              </MenuItem>
            ))}
          </TextField>
        </>
      )}
      {(value !== "" || unit !== "") && !required && (
        <Button size="small" onClick={() => onChange("", "")}>
          {t.estimate.clear}
        </Button>
      )}
      {required && value === "" && (
        <Typography variant="caption" color="warning.main">
          {t.estimate.requiredHint}
        </Typography>
      )}
    </Stack>
  );
}
