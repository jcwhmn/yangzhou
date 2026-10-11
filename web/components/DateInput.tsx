"use client";

import { useEffect, useRef, useState } from "react";
import { Box, FormLabel, IconButton, type SxProps, type Theme } from "@mui/material";
import ClearIcon from "@mui/icons-material/Clear";
import { t } from "@/lib/texts";
import { tokens } from "@/lib/tokens";

// 分段日期输入(spec 0019 / YPJ-19):mm/dd/yyyy 三段直录,替代原生 type="date"。
// 契约:onChange 只在「三段完整合法」或「清空」时外发;Esc 回滚草稿;空值点击预填今天,失焦即提交。
// e2e:容器 role=group(aria-label)+ data-value=已提交 ISO;段 aria-label=月/日/年(helpers fillDate/expectDate)。

type Segs = { mm: string; dd: string; yyyy: string };

const ORDER: (keyof Segs)[] = ["mm", "dd", "yyyy"];
const MAXLEN: Record<keyof Segs, number> = { mm: 2, dd: 2, yyyy: 4 };

const pad2 = (n: number) => String(n).padStart(2, "0");
const digits = (v: string, max: number) => v.replace(/\D/g, "").slice(0, max);

function parseIso(iso: string): Segs {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? "");
  return m ? { yyyy: m[1], mm: m[2], dd: m[3] } : { mm: "", dd: "", yyyy: "" };
}

function isoOf(s: Segs): string {
  return s.mm.length === 2 && s.dd.length === 2 && s.yyyy.length === 4 ? `${s.yyyy}-${s.mm}-${s.dd}` : "";
}

function validDate(s: Segs): boolean {
  const y = Number(s.yyyy);
  const m = Number(s.mm);
  const d = Number(s.dd);
  if (m < 1 || m > 12 || d < 1) return false;
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d;
}

function todaySegs(): Segs {
  const n = new Date();
  return { yyyy: String(n.getFullYear()), mm: pad2(n.getMonth() + 1), dd: pad2(n.getDate()) };
}

export function DateInput({
  label,
  ariaLabel,
  value,
  onChange,
  size,
  sx,
}: {
  label?: string;
  ariaLabel?: string;
  value: string;
  onChange: (v: string) => void;
  size?: "small";
  sx?: SxProps<Theme>;
}) {
  const segText: Record<keyof Segs, string> = { mm: t.date.month, dd: t.date.day, yyyy: t.date.year };
  const name = label ?? ariaLabel;
  const [segs, setSegs] = useState<Segs>(() => parseIso(value));
  const [error, setError] = useState(false);
  const focused = useRef(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const segRefs = {
    mm: useRef<HTMLInputElement>(null),
    dd: useRef<HTMLInputElement>(null),
    yyyy: useRef<HTMLInputElement>(null),
  };

  // value 外部变化(表单 reset 等)且未聚焦 → 草稿同步
  useEffect(() => {
    if (!focused.current) setSegs(parseIso(value));
  }, [value]);

  const draftEmpty = !segs.mm && !segs.dd && !segs.yyyy;
  const showClear = !!name && (value !== "" || !draftEmpty);

  // 失焦/Enter 的提交路径:全空→清空;不完整→保留草稿;组合非法→红框;完整合法→外发
  const commit = () => {
    if (draftEmpty) {
      if (value !== "") onChange("");
      return;
    }
    const iso = isoOf(segs);
    if (iso === "") return;
    if (!validDate(segs)) {
      setError(true);
      return;
    }
    if (iso !== value) onChange(iso);
  };

  const focusSeg = (k: keyof Segs) => segRefs[k].current?.focus();

  const onSegInput = (k: keyof Segs, v: string) => {
    const clean = digits(v, MAXLEN[k]);
    setError(false);
    setSegs((p) => ({ ...p, [k]: clean }));
    if (clean.length === MAXLEN[k]) {
      const next = ORDER[ORDER.indexOf(k) + 1];
      if (next) focusSeg(next);
    }
  };

  const onSegKeyDown = (k: keyof Segs, e: React.KeyboardEvent<HTMLInputElement>) => {
    const i = ORDER.indexOf(k);
    if ((e.key === "ArrowRight" || e.key === "/") && i < ORDER.length - 1) {
      e.preventDefault();
      focusSeg(ORDER[i + 1]);
    } else if (e.key === "ArrowLeft" && i > 0) {
      e.preventDefault();
      focusSeg(ORDER[i - 1]);
    } else if (e.key === "Backspace" && segs[k] === "" && i > 0) {
      focusSeg(ORDER[i - 1]);
    } else if (e.key === "Delete") {
      setError(false);
      setSegs((p) => ({ ...p, [k]: "" }));
    }
  };

  const onSegPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData.getData("text").trim();
    const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(text);
    const mdy = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text);
    if (!iso && !mdy) return; // 非完整日期,按普通入段
    e.preventDefault();
    setError(false);
    if (iso) {
      setSegs({ yyyy: iso[1], mm: pad2(Number(iso[2])), dd: pad2(Number(iso[3])) });
    } else if (mdy) {
      setSegs({ mm: pad2(Number(mdy[1])), dd: pad2(Number(mdy[2])), yyyy: mdy[3] });
    }
  };

  const fieldSx = {
    display: "flex",
    alignItems: "center",
    bgcolor: tokens.field,
    border: "1px solid transparent",
    borderRadius: tokens.radius,
    px: 1,
    py: size === "small" ? 0.25 : 0.5,
    "&:focus-within": { borderColor: tokens.border },
    "& .seg": {
      border: "none",
      outline: "none",
      background: "transparent",
      p: 0,
      textAlign: "center",
      fontSize: 14,
      color: "text.primary",
      "&::placeholder": { color: "text.secondary", opacity: 1 },
    },
    ...(error ? { borderColor: "error.main", "&:focus-within": { borderColor: "error.main" } } : {}),
  };

  return (
    <Box sx={[{ display: "inline-flex", flexDirection: "column" }, ...(Array.isArray(sx) ? sx : [sx])]}>
      {label && (
        <FormLabel aria-hidden sx={{ fontSize: 13, color: "text.secondary", mb: 0.25, ml: 0.5 }}>
          {label}
        </FormLabel>
      )}
      <Box
        ref={rootRef}
        role="group"
        aria-label={name}
        data-value={value}
        sx={fieldSx}
        onFocus={() => {
          focused.current = true;
        }}
        onBlur={(e) => {
          if (rootRef.current?.contains(e.relatedTarget as Node | null)) return;
          focused.current = false;
          commit();
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            commit();
          } else if (e.key === "Escape") {
            // 放弃编辑;拦下冒泡,免得 Dialog 把整个弹层关掉
            e.stopPropagation();
            setSegs(parseIso(value));
            setError(false);
          }
        }}
        onMouseDown={(e) => {
          const el = e.target as HTMLElement;
          if (el.tagName !== "INPUT" && !rootRef.current?.contains(el)) return;
          // 空值点击插当天(spec 0019):预填即草稿,失焦走 commit 即提交
          if (value === "" && draftEmpty) setSegs(todaySegs());
        }}
        onClick={() => {
          const ae = document.activeElement;
          if (ae instanceof HTMLElement && rootRef.current?.contains(ae)) return;
          const first = ORDER.find((k) => segs[k].length < MAXLEN[k]) ?? "yyyy";
          focusSeg(first);
        }}
      >
        <input
          className="seg"
          style={{ width: 26 }}
          inputMode="numeric"
          placeholder="mm"
          aria-label={segText.mm}
          value={segs.mm}
          onChange={(e) => onSegInput("mm", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("mm", e)}
          onPaste={onSegPaste}
          ref={segRefs.mm}
        />
        <Box component="span" sx={{ px: 0.25, color: "text.secondary" }}>/</Box>
        <input
          className="seg"
          style={{ width: 26 }}
          inputMode="numeric"
          placeholder="dd"
          aria-label={segText.dd}
          value={segs.dd}
          onChange={(e) => onSegInput("dd", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("dd", e)}
          onPaste={onSegPaste}
          ref={segRefs.dd}
        />
        <Box component="span" sx={{ px: 0.25, color: "text.secondary" }}>/</Box>
        <input
          className="seg"
          style={{ width: 44 }}
          inputMode="numeric"
          placeholder="yyyy"
          aria-label={segText.yyyy}
          value={segs.yyyy}
          onChange={(e) => onSegInput("yyyy", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("yyyy", e)}
          onPaste={onSegPaste}
          ref={segRefs.yyyy}
        />
        {showClear && (
          <IconButton
            size="small"
            aria-label={`清除 ${name}`}
            onMouseDown={(e) => e.preventDefault()}
            onClick={(e) => {
              e.stopPropagation();
              onChange("");
              setSegs({ mm: "", dd: "", yyyy: "" });
              setError(false);
            }}
          >
            <ClearIcon fontSize="small" />
          </IconButton>
        )}
      </Box>
    </Box>
  );
}
