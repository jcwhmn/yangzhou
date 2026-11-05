"use client";

// YPJ-48(spec 0056)最小前端:项目设置页的 group 类型管理(列/增/删)。
// 改名走 API 不做 UI(手工验证经 api.http);sprint/release 的组管理在专属功能里。
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItemText,
  Stack,
  TextField,
  Typography,
  FormControlLabel,
  Checkbox,
} from "@mui/material";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type GroupType = {
  typeKey: string;
  name: string;
  builtin: boolean;
  level: number | null;
  timed: boolean;
};
// spec 0097 S3:类型 workflow 只读形状(同 GroupWorkflowService.GroupStatusDto)
type WfStatus = {
  name: string;
  statusType: string;
  transitions: { function: string; target: string }[];
};

function wfTypeLabel(statusType: string): string {
  switch (statusType) {
    case "START":
      return t.wf.start;
    case "FINAL":
      return t.wf.final;
    case "ACTIVE":
      return t.wf.active;
    default:
      return statusType;
  }
}

function wfFnLabel(fn: string): string {
  switch (fn) {
    case "start":
      return t.wf.fnStart;
    case "complete":
      return t.wf.fnComplete;
    case "switch":
      return t.wf.fnSwitch;
    case "forward":
      return t.wf.fnForward;
    case "backward":
      return t.wf.fnBackward;
    case "cancel":
      return t.wf.fnCancel;
    default:
      return fn;
  }
}

export function GroupTypesPanel({
  projectKey,
  open,
  onClose,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
}) {
  const [types, setTypes] = useState<GroupType[]>([]);
  const [workflows, setWorkflows] = useState<
    Record<string, WfStatus[] | undefined>
  >({});
  const [typeKey, setTypeKey] = useState("");
  const [name, setName] = useState("");
  const [timed, setTimed] = useState(false);
  const [error, setError] = useState("");

  const load = () => {
    api<GroupType[]>(`/api/projects/${projectKey}/group-types`)
      .then(async (ts) => {
        setTypes(ts);
        // spec 0097 S3:workflow 只读展示,每类型一拉;失败不阻断类型管理(该类型不显示块)
        const entries = await Promise.all(
          ts.map(async (g) => {
            const wf = await api<WfStatus[]>(
              `/api/projects/${projectKey}/group-types/${g.typeKey}/workflow`,
            ).catch(() => undefined);
            return [g.typeKey, wf] as const;
          }),
        );
        setWorkflows(Object.fromEntries(entries));
      })
      .catch(() => setTypes([]));
  };

  useEffect(() => {
    if (open) {
      load();
      setTypeKey("");
      setName("");
      setTimed(false);
      setError("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  async function create() {
    try {
      await api(`/api/projects/${projectKey}/group-types`, {
        method: "POST",
        body: JSON.stringify({
          typeKey: typeKey.trim(),
          name: name.trim(),
          timed,
        }),
      });
      setTypeKey("");
      setName("");
      setError("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.groupTypes.failed);
    }
  }

  async function remove(typeKey: string) {
    try {
      await api(`/api/projects/${projectKey}/group-types/${typeKey}`, {
        method: "DELETE",
      });
      setError("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.groupTypes.failed);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t.groupTypes.title}</DialogTitle>
      <DialogContent>
        <Typography variant="caption" color="text.secondary">
          {t.groupTypes.hint}
        </Typography>
        <List dense>
          {types.map((g) => (
            <Box key={g.typeKey} component="li">
              <Box sx={{ display: "flex", alignItems: "center" }}>
                <ListItemText
                  primary={g.name}
                  secondary={g.typeKey}
                  sx={{ flex: 1 }}
                />
                {g.level != null && (
                  <Chip
                    size="small"
                    color="primary"
                    label={`L${g.level}`}
                    sx={{ mr: 1 }}
                  />
                )}
                {g.timed && (
                  <Chip
                    size="small"
                    label={t.groupTypes.timed}
                    sx={{ mr: 1 }}
                  />
                )}
                {g.builtin && (
                  <Chip
                    size="small"
                    label={t.groupTypes.builtin}
                    sx={{ mr: 1 }}
                  />
                )}
                {!g.builtin && (
                  <IconButton
                    size="small"
                    aria-label={t.groupTypes.deleteBtn}
                    onClick={() => remove(g.typeKey)}
                  >
                    <DeleteOutlineIcon fontSize="small" />
                  </IconButton>
                )}
              </Box>
              {/* spec 0097 S3:workflow 只读展示(状态 · statusType · 出边动作 → 目标) */}
              {workflows[g.typeKey] !== undefined && (
                <Box sx={{ pl: 2, pb: 1 }}>
                  {workflows[g.typeKey]!.length === 0 ? (
                    <Typography variant="caption" color="text.secondary">
                      {t.groupTypes.wfEmpty}
                    </Typography>
                  ) : (
                    workflows[g.typeKey]!.map((st) => (
                      <Typography
                        key={st.name}
                        variant="caption"
                        component="div"
                        color="text.secondary"
                      >
                        {st.name} · {wfTypeLabel(st.statusType)}
                        {st.transitions.length > 0 &&
                          ` · ${st.transitions
                            .map(
                              (tr) =>
                                `${wfFnLabel(tr.function)} → ${tr.target}`,
                            )
                            .join("、")}`}
                      </Typography>
                    ))
                  )}
                </Box>
              )}
            </Box>
          ))}
          {types.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              {t.groupTypes.empty}
            </Typography>
          )}
        </List>
        <Stack
          direction="row"
          spacing={1}
          sx={{ mt: 1 }}
          component="form"
          onSubmit={(e) => {
            e.preventDefault();
            if (typeKey.trim() && name.trim()) create();
          }}
        >
          <TextField
            size="small"
            label={t.groupTypes.typeKey}
            value={typeKey}
            onChange={(e) => setTypeKey(e.target.value)}
            sx={{ width: 150 }}
          />
          <TextField
            size="small"
            label={t.groupTypes.name}
            value={name}
            onChange={(e) => setName(e.target.value)}
            sx={{ flex: 1 }}
          />
          <FormControlLabel
            control={
              <Checkbox
                checked={timed}
                onChange={(e) => setTimed(e.target.checked)}
              />
            }
            label={t.groupTypes.timed}
            sx={{ mr: 1 }}
          />
          <Button
            type="submit"
            variant="contained"
            disabled={!typeKey.trim() || !name.trim()}
          >
            {t.groupTypes.create}
          </Button>
        </Stack>
        {error && (
          <Alert severity="error" sx={{ mt: 1 }}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t.groupTypes.close}</Button>
      </DialogActions>
    </Dialog>
  );
}
