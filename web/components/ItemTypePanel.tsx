"use client";

// YPJ-177(spec 0123):项目页图标行弹出的 item 类型管理面板(增/改名/删;builtin 删钮置灰)。
// 壳镜像 GroupTypesPanel;typeKey slug 约束由服务端校验,前端不重做;改 typeKey 不提供(标识符不可变)。
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
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import CheckIcon from "@mui/icons-material/Check";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { t } from "@/lib/texts";

type ItemType = {
  typeKey: string;
  name: string;
  builtin: boolean;
};

export function ItemTypePanel({
  projectKey,
  open,
  onClose,
  onChanged,
}: {
  projectKey: string;
  open: boolean;
  onClose: () => void;
  /** 变更后回调(如刷新 item 表单下拉缓存)。 */
  onChanged?: () => void;
}) {
  const [types, setTypes] = useState<ItemType[]>([]);
  const [typeKey, setTypeKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);
  const [draftName, setDraftName] = useState("");
  const [confirmingKey, setConfirmingKey] = useState<string | null>(null);

  const load = () =>
    api<ItemType[]>(`/api/projects/${projectKey}/item-types`)
      .then(setTypes)
      .catch(() => setTypes([]));

  useEffect(() => {
    if (open) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectKey]);

  const submit = async () => {
    try {
      await api(`/api/projects/${projectKey}/item-types`, {
        method: "POST",
        body: JSON.stringify({ typeKey: typeKey.trim(), name: name.trim() }),
      });
      setTypeKey("");
      setName("");
      setError("");
      load();
      onChanged?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.itemTypes.failed);
    }
  };

  const rename = async (typeKey: string) => {
    try {
      await api(`/api/projects/${projectKey}/item-types/${typeKey}`, {
        method: "PATCH",
        body: JSON.stringify({ name: draftName.trim() }),
      });
      setRenaming(null);
      setError("");
      load();
      onChanged?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.itemTypes.failed);
    }
  };

  const remove = async (typeKey: string) => {
    // 两段确认(镜像 MilestonePage 删除):首击标确认,再击真删
    if (confirmingKey !== typeKey) {
      setConfirmingKey(typeKey);
      return;
    }
    try {
      await api(`/api/projects/${projectKey}/item-types/${typeKey}`, {
        method: "DELETE",
      });
      setConfirmingKey(null);
      setError("");
      load();
      onChanged?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : t.itemTypes.failed);
      setConfirmingKey(null);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{t.itemTypes.title}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 1 }}>
            {error}
          </Alert>
        )}
        <List dense disablePadding>
          {types.map((tp) => (
            <ListItem
              key={tp.typeKey}
              data-testid="item-type-row"
              disablePadding
              secondaryAction={
                <Stack direction="row" spacing={0.5}>
                  {!tp.builtin && (
                    <IconButton
                      size="small"
                      aria-label={`改名 ${tp.typeKey}`}
                      onClick={() => {
                        setRenaming(tp.typeKey);
                        setDraftName(tp.name);
                      }}
                    >
                      <EditOutlinedIcon fontSize="small" />
                    </IconButton>
                  )}
                  <IconButton
                    size="small"
                    aria-label={`删除 ${tp.typeKey}`}
                    disabled={tp.builtin}
                    color={confirmingKey === tp.typeKey ? "error" : "default"}
                    onClick={() => remove(tp.typeKey)}
                  >
                    <DeleteOutlineIcon fontSize="small" />
                  </IconButton>
                </Stack>
              }
            >
              <ListItemText
                primary={
                  renaming === tp.typeKey ? (
                    <Stack direction="row" spacing={0.5} alignItems="center">
                      <TextField
                        size="small"
                        value={draftName}
                        autoFocus
                        onChange={(e) => setDraftName(e.target.value)}
                        sx={{ width: 160 }}
                      />
                      <IconButton
                        size="small"
                        aria-label={`保存 ${tp.typeKey}`}
                        onClick={() => rename(tp.typeKey)}
                      >
                        <CheckIcon fontSize="small" />
                      </IconButton>
                    </Stack>
                  ) : (
                    <Typography variant="body2">
                      {tp.name}
                      <Typography
                        component="span"
                        variant="caption"
                        color="text.secondary"
                        sx={{ ml: 1 }}
                      >
                        {tp.typeKey}
                      </Typography>
                    </Typography>
                  )
                }
              />
              {tp.builtin && <Chip size="small" label={t.itemTypes.builtin} />}
            </ListItem>
          ))}
        </List>
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <TextField
            size="small"
            label={t.itemTypes.key}
            value={typeKey}
            onChange={(e) => setTypeKey(e.target.value)}
            sx={{ width: 140 }}
          />
          <TextField
            size="small"
            label={t.itemTypes.nameLabel}
            value={name}
            onChange={(e) => setName(e.target.value)}
            sx={{ flex: 1 }}
          />
          <Button
            variant="outlined"
            onClick={submit}
            disabled={!typeKey.trim() || !name.trim()}
          >
            {t.itemTypes.add}
          </Button>
        </Stack>
        <Box sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary">
            {t.itemTypes.hint}
          </Typography>
        </Box>
      </DialogContent>
    </Dialog>
  );
}
