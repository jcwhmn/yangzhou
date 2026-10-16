"use client";

import { useRef, useState } from "react";
import {
  Chip,
  InputAdornment,
  IconButton,
  Menu,
  MenuItem,
  TextField,
  type SxProps,
  type Theme,
} from "@mui/material";
import ClearIcon from "@mui/icons-material/Clear";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import CheckIcon from "@mui/icons-material/Check";

// 统一选择控件(spec 0018 / YPJ-18):替代 MUI Select / TextField-select。
// e2e 硬约束:触发器 role=combobox、弹层 listbox、选项 option——openSelect/selectOption 依赖此角色链。
export type DropdownOption = { value: string; label: string; disabled?: boolean; chip?: { label: string; color: "primary" | "warning" | "default" } };

export function Dropdown({
  label,
  placeholder,
  value,
  onChange,
  options,
  clearable = false,
  size,
  fullWidth,
  disabled = false,
  ariaLabel,
  sx,
}: {
  label?: string;
  placeholder?: string;
  value: string;
  onChange: (v: string) => void;
  options: DropdownOption[];
  clearable?: boolean;
  size?: "small" | "medium";
  fullWidth?: boolean;
  disabled?: boolean;
  ariaLabel?: string; // 无 label 场景的可访问名(e2e getByRole name)
  sx?: SxProps<Theme>;
}) {
  const [open, setOpen] = useState(false);
  const anchorRef = useRef<HTMLDivElement>(null);
  const display = options.find((o) => o.value === value)?.label ?? "";

  return (
    <div ref={anchorRef}>
      <TextField
        label={label}
        placeholder={placeholder}
        value={display}
        size={size}
        fullWidth={fullWidth}
        disabled={disabled}
        onClick={() => !disabled && setOpen(true)}
        onKeyDown={(e) => {
          if (!disabled && (e.key === "Enter" || e.key === " " || e.key === "ArrowDown")) {
            e.preventDefault();
            setOpen(true);
          }
        }}
        sx={[
          ...(Array.isArray(sx) ? sx : [sx]),
          { "& .MuiInputBase-input": { cursor: "pointer" } },
        ]}
        slotProps={{
          inputLabel: label ? { shrink: true } : undefined,
          // v7:原生 input 属性走 htmlInput 槽(inputProps 路径已 deprecated,角色会落错元素)
          htmlInput: {
            role: "combobox",
            "aria-expanded": open,
            "aria-haspopup": "listbox",
            ...(ariaLabel ? { "aria-label": ariaLabel } : {}),
          },
          input: {
            readOnly: true,
            endAdornment: (
              <InputAdornment position="end">
                {clearable && value !== "" && !disabled ? (
                  <IconButton
                    size="small"
                    aria-label={`清除 ${label ?? ariaLabel ?? ""}`}
                    onMouseDown={(e) => e.stopPropagation()}
                    onClick={(e) => {
                      e.stopPropagation();
                      onChange("");
                    }}
                  >
                    <ClearIcon fontSize="small" />
                  </IconButton>
                ) : (
                  <ExpandMoreIcon
                    fontSize="small"
                    sx={{ color: "text.secondary", rotate: open ? "180deg" : "none", transition: "rotate 150ms" }}
                  />
                )}
              </InputAdornment>
            ),
          },
        }}
      />
      <Menu
        anchorEl={anchorRef.current}
        open={open}
        onClose={() => setOpen(false)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        transformOrigin={{ vertical: "top", horizontal: "left" }}
        slotProps={{ paper: { sx: { maxHeight: 360 } }, list: { role: "listbox", "aria-label": label ?? ariaLabel } }}
      >
        {options.map((o) => (
          <MenuItem
            key={o.value}
            role="option"
            disabled={o.disabled}
            selected={o.value === value}
            onClick={() => {
              onChange(o.value);
              setOpen(false);
            }}
          >
            {o.label}
            {o.chip && <Chip size="small" label={o.chip.label} color={o.chip.color} variant="outlined" sx={{ ml: 1 }} />}
            {o.value === value && <CheckIcon fontSize="small" sx={{ ml: 1 }} />}
          </MenuItem>
        ))}
      </Menu>
    </div>
  );
}
