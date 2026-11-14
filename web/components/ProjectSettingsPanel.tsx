"use client";

// YPJ-151:项目设置——release/milestone 启停(仅存取+UI 门控;服务端不拦截,见 spec 0102)
// YPJ-180:工作量刻度项目级配置(spec 0126)——档位增删改(key/label/value/unit)+ required 开关
import {
  Alert,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Button,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/DeleteOutline";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type EstimateScale = {
  key: string;
  label: string;
  value: number;
  unit: string;
};

// 与后端 EstimateScales.DEFAULT 同源占位(spec 0126;真源=后端,读时以响应为准)
const DEFAULT_SCALES: EstimateScale[] = [
  { key: "xs", label: "微", value: 2, unit: "day" },
  { key: "s", label: "小", value: 3, unit: "day" },
  { key: "m", label: "中", value: 5, unit: "day" },
  { key: "l", label: "大", value: 8, unit: "day" },
  { key: "xl", label: "超大", value: 13, unit: "day" },
];
const KEY_RE = /^[a-z0-9_-]{1,32}$/;
// 单位封闭枚举,与后端 EstimateScales.UNITS 同源(用户拍板方案 B);进计算,显示名文案外置
const UNITS = ["hour", "day", "week"] as const;

function rowError(s: EstimateScale, all: EstimateScale[]): string | null {
  if (!KEY_RE.test(s.key)) return "key 须 1–32 位小写字母/数字/-/_";
  if (all.filter((x) => x.key === s.key).length > 1) return "key 重复";
  if (!s.label.trim() || s.label.length > 16) return "名称须 1–16 字符";
  if (!(s.value > 0 && s.value <= 999)) return "数值须 (0,999]";
  if (!UNITS.includes(s.unit as (typeof UNITS)[number]))
    return `单位须为 ${UNITS.join("/")}`;
  return null;
}

export function ProjectSettingsPanel({
  projectKey,
  open,
  onClose,
  onChanged,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [release, setRelease] = useState(true);
  const [milestone, setMilestone] = useState(true);
  const [scales, setScales] = useState<EstimateScale[]>(DEFAULT_SCALES);
  const [scalesIsDefault, setScalesIsDefault] = useState(true);
  const [newIdx, setNewIdx] = useState<number[]>([]); // 本次新增行:key 可编辑;已存档 key 是稳定标识锁定
  const [required, setRequired] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  // 同轮 open 去重 + 首响落定:负载下 effect 可能重入,后到的重复响应不得冲值
  const lastRound = useRef("");
  const settled = useRef(false);

  useEffect(() => {
    if (!open) {
      // 关闭即复位:重开首帧控件不渲染,防残留旧值
      lastRound.current = "";
      setLoaded(false);
      return;
    }
    if (lastRound.current === projectKey) return; // 同轮重复触发
    lastRound.current = projectKey;
    settled.current = false;
    setLoaded(false);
    setError("");
    api<{
      releaseEnabled: boolean;
      milestoneEnabled: boolean;
      estimateScales: EstimateScale[];
      estimateScalesIsDefault: boolean;
      estimateRequired: boolean;
    }>(`/api/projects/${projectKey}`)
      .then((p) => {
        if (settled.current) return;
        settled.current = true;
        setRelease(p.releaseEnabled);
        setMilestone(p.milestoneEnabled);
        setScales(p.estimateScales);
        setScalesIsDefault(p.estimateScalesIsDefault);
        setNewIdx([]);
        setRequired(p.estimateRequired);
        setLoaded(true);
      })
      .catch(() => {
        if (settled.current) return;
        settled.current = true;
        setError(t.settings.loadFailed);
        setLoaded(true);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  function updateRow(i: number, patch: Partial<EstimateScale>) {
    setScales((prev) => prev.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  }

  const rowErrors = scales.map((s) => rowError(s, scales));
  const formInvalid = rowErrors.some((e) => e !== null) || scales.length === 0;

  async function save() {
    try {
      await api(`/api/projects/${projectKey}/settings`, {
        method: "PATCH",
        body: JSON.stringify({
          releaseEnabled: release,
          milestoneEnabled: milestone,
          estimateScales: scales,
          estimateRequired: required,
        }),
      });
      setError("");
      onChanged();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.settings.saveFailed);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t.settings.title}</DialogTitle>
      <DialogContent>
        {/* 加载完成才渲染控件:控件在 settle 帧前不存在,点击意图不会被
            响应落地前后的再渲染吞掉(e2e 全量负载实锤:先渲染+disabled 门禁
            两轮补丁仍有窗口,2026-11-12 根治) */}
        {!loaded ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {t.settings.loading}
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 1 }}>
            <FormControlLabel
              control={
                <Switch
                  checked={release}
                  onChange={(e) => setRelease(e.target.checked)}
                />
              }
              label={t.settings.release}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={milestone}
                  onChange={(e) => setMilestone(e.target.checked)}
                />
              }
              label={t.settings.milestone}
            />
            <Typography variant="subtitle2" sx={{ mt: 1 }}>
              {t.settings.estimateTitle}
            </Typography>
            {scales.map((s, i) => (
              <Stack key={i} direction="row" spacing={1} alignItems="center">
                <TextField
                  size="small"
                  label="key"
                  value={s.key}
                  error={
                    rowErrors[i] !== null &&
                    (rowErrors[i]!.includes("key") || false)
                  }
                  // 已存档位 key 是稳定标识(item 侧将来引用),只允许新档编辑;默认占位五档 key 也可改
                  disabled={
                    !(scalesIsDefault && i < DEFAULT_SCALES.length) &&
                    !newIdx.includes(i)
                  }
                  onChange={(e) => updateRow(i, { key: e.target.value })}
                  sx={{ width: 110 }}
                />
                <TextField
                  size="small"
                  label="名称"
                  value={s.label}
                  error={
                    rowErrors[i] !== null && rowErrors[i]!.includes("名称")
                  }
                  onChange={(e) => updateRow(i, { label: e.target.value })}
                  sx={{ width: 110 }}
                />
                <TextField
                  size="small"
                  label="数值"
                  type="number"
                  value={s.value}
                  error={
                    rowErrors[i] !== null && rowErrors[i]!.includes("数值")
                  }
                  onChange={(e) =>
                    updateRow(i, { value: Number(e.target.value) })
                  }
                  sx={{ width: 90 }}
                  inputProps={{ min: 0.5, max: 999, step: "any" }}
                />
                <TextField
                  select
                  size="small"
                  label="单位"
                  value={s.unit}
                  error={
                    rowErrors[i] !== null && rowErrors[i]!.includes("单位")
                  }
                  onChange={(e) => updateRow(i, { unit: e.target.value })}
                  sx={{ width: 90 }}
                >
                  {UNITS.map((u) => (
                    <MenuItem key={u} value={u}>
                      {t.settings.unitNames[u]}
                    </MenuItem>
                  ))}
                </TextField>
                <IconButton
                  aria-label="删除档位"
                  disabled={scales.length <= 1}
                  onClick={() =>
                    setScales((prev) => prev.filter((_, j) => j !== i))
                  }
                >
                  <DeleteIcon />
                </IconButton>
              </Stack>
            ))}
            {rowErrors.some((e) => e !== null) && (
              <Typography variant="caption" color="error">
                {rowErrors.find((e) => e !== null)}
              </Typography>
            )}
            <Stack direction="row" spacing={1}>
              <Button
                size="small"
                disabled={scales.length >= 10}
                onClick={() => {
                  setScales((prev) => [
                    ...prev,
                    {
                      key: `k${Date.now().toString(36).slice(-4)}`,
                      label: "",
                      value: 1,
                      unit: "day",
                    },
                  ]);
                  setNewIdx((prev) => [...prev, scales.length]);
                }}
              >
                {t.settings.estimateAdd}
              </Button>
              <Button
                size="small"
                onClick={() => {
                  setScales(DEFAULT_SCALES.map((s) => ({ ...s })));
                  setNewIdx([]);
                }}
              >
                {t.settings.estimateReset}
              </Button>
            </Stack>
            <FormControlLabel
              control={
                <Switch
                  checked={required}
                  onChange={(e) => setRequired(e.target.checked)}
                />
              }
              label={t.settings.estimateRequired}
            />
            <Alert severity="info" variant="outlined" sx={{ py: 0.5 }}>
              {t.settings.hint}
            </Alert>
          </Stack>
        )}
        {error && <Alert severity="error">{error}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.settings.cancel}</Button>
        <Button
          variant="contained"
          onClick={save}
          disabled={!loaded || formInvalid}
        >
          {t.settings.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
