"use client";

// YPJ-48(spec 0056)最小前端:项目页图标行弹出的分组类型管理面板(列/增/删)。
// 改名走 API 不做 UI(手工验证经 api.http);sprint/release 的组管理在专属功能里。
// spec 0115(YPJ-164,§四修订):workflow 两态同一行骨架——
// W-RO(默认)= 出边 4 槽展示行(fn/target/label/icon 同编辑态槽位,纯展示,零点击可见);
// W-EDIT(点笔)= 同槽位变控件 + 删边 + 加出边 + 保存/取消;无二级弹出。
// release 类型不展示(非用户管理的分组类型);2026-11-09 用户拍板。
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
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
  Select,
  MenuItem,
} from "@mui/material";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";
import { WF_FN_DICT, wfFnIcon, wfFnLabel } from "@/lib/groupWorkflow";

type GroupType = {
  typeKey: string;
  name: string;
  builtin: boolean;
  level: number | null;
  timed: boolean;
};
// spec 0097 S3:类型 workflow 只读形状(同 GroupWorkflowService.GroupStatusDto);spec 0115 增 label/icon
type WfTransition = {
  function: string;
  target: string;
  label?: string | null;
  icon?: string | null;
};
type WfStatus = {
  name: string;
  statusType: string;
  transitions: WfTransition[];
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

// W-RO 出边 4 槽展示行:槽位/顺序/宽度与 W-EDIT 控件一致(90/100/110/70),纯展示不可编辑
function EdgeView({
  fn,
  target,
  label,
  icon,
}: {
  embedded?: boolean;
  fn: string;
  target: string;
  label: string;
  icon: string;
}) {
  const slot = (v: string, min: number) => (
    <Box
      sx={{
        minWidth: min,
        height: 40,
        display: "flex",
        alignItems: "center",
        px: 1,
        border: 1,
        borderColor: "divider",
        borderRadius: 1,
        fontSize: 13,
        overflow: "hidden",
        whiteSpace: "nowrap",
      }}
    >
      {v}
    </Box>
  );
  return (
    <Stack direction="row" spacing={0.5} sx={{ pl: 1, my: 0.5 }}>
      {slot(fn, 90)}
      {slot(target, 100)}
      {slot(label, 110)}
      {slot(icon, 70)}
    </Stack>
  );
}

export function GroupTypesPanel({
  embedded = false,
  projectKey,
  open,
  onClose,
}: {
  embedded?: boolean;
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
  // spec 0115:W-EDIT 单类型粒度(同时至多一个);草稿生命周期 = 面板会话,关面板即弃
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<WfStatus[]>([]);
  const [wfError, setWfError] = useState("");
  // 新建成功后焦点回 typeKey:重置会 disable 聚焦中的新增按钮,焦点掉到 body,
  // Esc(打在 body 上不进弹层子树)就关不掉面板(问题处理记录 #5,2026-11-13 实锤)
  const typeKeyRef = useRef<HTMLInputElement>(null);

  const load = () => {
    api<GroupType[]>(`/api/projects/${projectKey}/group-types`)
      .then(async (ts) => {
        // release 非用户管理的分组类型(专属功能域),面板不展示
        const visible = ts.filter((g) => g.typeKey !== "release");
        setTypes(visible);
        // spec 0097 S3:workflow 只读展示,每类型一拉;失败不阻断类型管理(该类型不显示块)
        const entries = await Promise.all(
          visible.map(async (g) => {
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
      setEditing(null);
      setDraft([]);
      setWfError("");
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
      typeKeyRef.current?.focus();
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

  // ---------- spec 0115:workflow 编辑(W-EDIT) ----------

  function startEdit(typeKey: string) {
    // 互斥:进入新类型编辑即静默取消他类型草稿
    setEditing(typeKey);
    // 预填:label/icon 缺省取数据字典(用户可改;保存后落库)
    setDraft(
      (workflows[typeKey] ?? []).map((st) => ({
        ...st,
        transitions: st.transitions.map((tr) => ({
          ...tr,
          label: tr.label ?? wfFnLabel(tr.function),
          icon: tr.icon ?? wfFnIcon(tr.function),
        })),
      })),
    );
    setWfError("");
  }

  function cancelEdit() {
    setEditing(null);
    setDraft([]);
    setWfError("");
  }

  async function saveEdit(typeKey: string) {
    try {
      await api(`/api/projects/${projectKey}/group-types/${typeKey}/workflow`, {
        method: "PUT",
        body: JSON.stringify(
          draft.map((st) => ({
            name: st.name,
            transitions: st.transitions.map((tr) => ({
              function: tr.function,
              target: tr.target,
              ...(tr.label ? { label: tr.label } : {}),
              ...(tr.icon ? { icon: tr.icon } : {}),
            })),
          })),
        ),
      });
      cancelEdit();
      load();
    } catch (e) {
      setWfError(e instanceof Error ? e.message : t.groupTypes.wfFailed);
    }
  }

  function setEdge(si: number, ti: number, tr: WfTransition) {
    setDraft(
      draft.map((st, i) =>
        i === si
          ? {
              ...st,
              transitions: st.transitions.map((e, j) => (j === ti ? tr : e)),
            }
          : st,
      ),
    );
  }

  function addEdge(si: number) {
    setDraft(
      draft.map((st, i) =>
        i === si
          ? {
              ...st,
              transitions: [
                ...st.transitions,
                {
                  function: "switch",
                  target: draft[0]?.name ?? st.name,
                  label: wfFnLabel("switch"),
                  icon: wfFnIcon("switch"),
                },
              ],
            }
          : st,
      ),
    );
  }

  function delEdge(si: number, ti: number) {
    setDraft(
      draft.map((st, i) =>
        i === si
          ? { ...st, transitions: st.transitions.filter((_, j) => j !== ti) }
          : st,
      ),
    );
  }

  // YPJ-187:embedded=设置中枢内容态(无 Dialog 壳);缺省保留原独立弹层
  const body = (
    <>
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
                {editing === g.typeKey ? (
                  <>
                    <Button
                      size="small"
                      variant="contained"
                      onClick={() => saveEdit(g.typeKey)}
                    >
                      {t.groupTypes.wfSave}
                    </Button>
                    <Button size="small" onClick={cancelEdit} sx={{ mr: 1 }}>
                      {t.groupTypes.wfCancel}
                    </Button>
                  </>
                ) : (
                  workflows[g.typeKey] !== undefined && (
                    <IconButton
                      size="small"
                      aria-label={t.groupTypes.wfEdit}
                      onClick={() => startEdit(g.typeKey)}
                    >
                      <EditOutlinedIcon fontSize="small" />
                    </IconButton>
                  )
                )}
              </Box>
              {/* spec 0115 §四:W-RO = 出边 4 槽展示行(同编辑态骨架,零点击可见)/ W-EDIT = 同槽位变控件 */}
              {workflows[g.typeKey] !== undefined && editing !== g.typeKey && (
                <Box sx={{ pl: 2, pb: 1 }}>
                  {workflows[g.typeKey]!.length === 0 ? (
                    <Typography variant="caption" color="text.secondary">
                      {t.groupTypes.wfEmpty}
                    </Typography>
                  ) : (
                    workflows[g.typeKey]!.map((st) => (
                      <Box key={st.name} sx={{ mb: 0.5 }}>
                        <Typography
                          variant="caption"
                          component="div"
                          color="text.secondary"
                        >
                          {st.name} · {wfTypeLabel(st.statusType)}
                        </Typography>
                        {st.transitions.map((tr, ti) => (
                          <EdgeView
                            key={ti}
                            fn={tr.function}
                            target={tr.target}
                            label={tr.label || wfFnLabel(tr.function)}
                            icon={tr.icon || wfFnIcon(tr.function)}
                          />
                        ))}
                      </Box>
                    ))
                  )}
                </Box>
              )}
              {workflows[g.typeKey] === undefined && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ pl: 2, pb: 1, display: "block" }}
                >
                  {t.groupTypes.wfLoadFail}
                </Typography>
              )}
              {editing === g.typeKey && (
                <Box sx={{ pl: 2, pb: 1 }}>
                  {draft.map((st, si) => (
                    <Box key={st.name} sx={{ mb: 1 }}>
                      <Typography
                        variant="caption"
                        component="div"
                        color="text.secondary"
                      >
                        {st.name} · {wfTypeLabel(st.statusType)}
                      </Typography>
                      {st.transitions.map((tr, ti) => (
                        <Stack
                          key={ti}
                          direction="row"
                          spacing={0.5}
                          sx={{ pl: 1, alignItems: "center", my: 0.5 }}
                        >
                          <Select
                            size="small"
                            value={tr.function}
                            onChange={(e) => {
                              const fn = e.target.value;
                              // 换函数即重预填 label/icon(可再改)
                              setEdge(si, ti, {
                                ...tr,
                                function: fn,
                                label: wfFnLabel(fn),
                                icon: wfFnIcon(fn),
                              });
                            }}
                            sx={{ minWidth: 90 }}
                          >
                            {Object.keys(WF_FN_DICT).map((fn) => (
                              <MenuItem key={fn} value={fn}>
                                {fn}
                              </MenuItem>
                            ))}
                          </Select>
                          <Select
                            size="small"
                            value={tr.target}
                            onChange={(e) =>
                              setEdge(si, ti, { ...tr, target: e.target.value })
                            }
                            sx={{ minWidth: 100 }}
                          >
                            {draft.map((s) => (
                              <MenuItem key={s.name} value={s.name}>
                                {s.name}
                              </MenuItem>
                            ))}
                          </Select>
                          <TextField
                            size="small"
                            placeholder={t.groupTypes.wfLabelPh}
                            value={tr.label ?? ""}
                            onChange={(e) =>
                              setEdge(si, ti, { ...tr, label: e.target.value })
                            }
                            sx={{ width: 110 }}
                          />
                          <Select
                            size="small"
                            value={tr.icon ?? ""}
                            onChange={(e) =>
                              setEdge(si, ti, { ...tr, icon: e.target.value })
                            }
                            sx={{ minWidth: 70 }}
                          >
                            <MenuItem value="">
                              {t.groupTypes.wfIconNone}
                            </MenuItem>
                            {Object.entries(WF_FN_DICT).map(([fn, d]) => (
                              <MenuItem key={fn} value={d.icon}>
                                {d.icon}
                              </MenuItem>
                            ))}
                          </Select>
                          <IconButton
                            size="small"
                            aria-label={t.groupTypes.deleteBtn}
                            onClick={() => delEdge(si, ti)}
                          >
                            <DeleteOutlineIcon fontSize="small" />
                          </IconButton>
                        </Stack>
                      ))}
                      <Button size="small" onClick={() => addEdge(si)}>
                        {t.groupTypes.wfAddEdge}
                      </Button>
                    </Box>
                  ))}
                  {wfError && (
                    <Alert severity="error" sx={{ mt: 1 }}>
                      {wfError}
                    </Alert>
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
            inputRef={typeKeyRef}
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
    </>
  );
  if (embedded) return body;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t.groupTypes.title}</DialogTitle>
      {body}
    </Dialog>
  );
}
