"use client";

import { useEffect, useRef, useState } from "react";
import { flushSync } from "react-dom";
import { Box, IconButton, Popover, Typography, type SxProps, type Theme } from "@mui/material";
import ClearIcon from "@mui/icons-material/Clear";
import DateRangeIcon from "@mui/icons-material/DateRange";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import KeyboardDoubleArrowLeftIcon from "@mui/icons-material/KeyboardDoubleArrowLeft";
import KeyboardDoubleArrowRightIcon from "@mui/icons-material/KeyboardDoubleArrowRight";
import { t } from "@/lib/texts";
import { tokens } from "@/lib/tokens";

// 分段日期输入(spec 0019/0029 / YPJ-19/34+23):mm/dd/yyyy 三段直录 + mini 月历,替代原生 type="date"。
// 契约:onChange 只在「三段完整且日期合法」或「清空」时外发;段内非法 digit 即拒(PRD_V2 §6.1/§7.1);
// 跨字段非法 commit 阻止 + focus 回问题段(§10.3);空控件聚焦预填今天仅作基准,未输入失焦不产生值(§10.2);
// Esc 回滚草稿;焦点/键入进位/点击落点均以单 digit 选中呈现,键入即覆写(用户拍板 2026-10-13)。
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

function daysInMonth(y: number, m: number): number {
  return new Date(y, m, 0).getDate();
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
  const [calOpen, setCalOpen] = useState(false);
  const [invalidSeg, setInvalidSeg] = useState<keyof Segs | null>(null);
  const dirty = useRef(false); // 本焦点期是否有过真实写入(§10.2 空进空出判据)
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

  // 提交(spec 0029 §10):不完整保留草稿;完整 → 跨字段验证(§10.3),非法不提交、focus 回问题段;
  // 空进空出(§10.2):本焦点期无真实写入且外部为空 → 还原空直接离开;
  // 年份短写补全:mm/dd 已满而年 1–3 位(27→2027,2 位按 50→1900 pivot;3 位前补 0)
  const commit = () => {
    if (draftEmpty) {
      if (value !== "") onChange("");
      return;
    }
    if (value === "" && !dirty.current) {
      setSegs({ mm: "", dd: "", yyyy: "" });
      return;
    }
    let { yyyy } = segs;
    if (segs.mm.length === 2 && segs.dd.length === 2 && yyyy.length > 0 && yyyy.length < 4) {
      const n = Number(yyyy);
      yyyy =
        yyyy.length === 1
          ? String(2000 + n)
          : yyyy.length === 2
            ? String(n >= 50 ? 1900 + n : 2000 + n)
            : yyyy.padStart(4, "0");
    }
    const full = { ...segs, yyyy };
    if (isoOf(full) === "") return;
    const y = Number(full.yyyy);
    const m = Number(full.mm);
    const d = Number(full.dd);
    const problem: keyof Segs | null = m < 1 || m > 12 ? "mm" : d < 1 || d > daysInMonth(y, m) ? "dd" : null;
    if (problem) {
      setInvalidSeg(problem);
      focusSeg(problem, true);
      return; // §10.3:不提交、value 不动、focus 定位问题段(blur 场景即拉回)
    }
    setInvalidSeg(null);
    const niso = isoOf(full);
    if (niso !== value) onChange(niso);
    setSegs(parseIso(niso));
  };

  // digit 选中(overwrite 视觉):pos 处有数字则选它,段尾仅落 caret
  const selectDigitEl = (el: HTMLInputElement, pos: number) => {
    const end = Math.min(pos + 1, el.value.length);
    el.setSelectionRange(pos, Math.max(end, pos));
  };
  const focusSeg = (k: keyof Segs, atStart = false) => {
    const el = segRefs[k].current;
    if (!el) return;
    el.focus();
    selectDigitEl(el, atStart ? 0 : Math.max(el.value.length - 1, 0));
  };

  // onChange 兜底(Playwright fill / IME / 粘贴单段):整段智能解读——1 位=十位槽(留段或智能个位 0X),
  // 2 位=满段跳下一段;成段非法(00/月>12/日>31)拒绝不应用(§6.1/§7.1);年逐位。真实键入走 writeDigit(keydown 拦截),不经此路径。
  const onSegInput = (k: keyof Segs, v: string) => {
    const clean = digits(v, MAXLEN[k]);
    if (k === "yyyy") {
      setSegs((p) => ({ ...p, yyyy: clean }));
      return;
    }
    if (clean === "") {
      setSegs((p) => ({ ...p, [k]: "" }));
      return;
    }
    const num = Number(clean);
    if (clean.length === 2 && (num === 0 || (k === "mm" && num > 12) || (k === "dd" && num > 31))) {
      setInvalidSeg(k); // §6.1/§7.1:成段非法拒绝(不钳制)
      return;
    }
    const next = ORDER[ORDER.indexOf(k) + 1];
    dirty.current = true;
    setInvalidSeg(null);
    if (clean.length === 1 && Number(clean) <= (k === "mm" ? 1 : 3)) {
      setSegs((p) => ({ ...p, [k]: clean }));
    } else {
      const val = clean.length === 1 ? `0${clean}` : clean;
      setSegs((p) => ({ ...p, [k]: val }));
      if (next) focusSeg(next, true);
    }
  };

  // 段聚焦:无整段选中(8 位独立,点击/键入都逐位);空值聚焦预填今天(click/Tab 同权),光标落本段位 1
  // flushSync 立即提交渲染再落光标——异步拉回(rAF)与 fill 的 insertText 有竞态,已证 flaky
  const onSegFocus = (e: React.FocusEvent<HTMLInputElement>) => {
    if (value === "" && draftEmpty) {
      setSegs(todaySegs());
      flushSync(() => {
        /* 立即提交预填渲染,再落光标 */
      });
      const el = e.currentTarget;
      if (document.activeElement === el) selectDigitEl(el, 0);
    }
  };

  // 逐位写入(spec 0019 §1.3,8 位独立):数字键覆写当前位、光标进一位;
  // 十位遇超界数字智能个位整段 0X 跳段;个位写满跳下一段位 1;日上限 31;年满 4 覆写末位。
  // cur 取 el.value(DOM 地面真值,含自然删除后的状态);先直写 el.value 再同步 state,防受控重渲染重置光标。
  const writeDigit = (k: keyof Segs, pos: number, d: number) => {
    const el = segRefs[k].current;
    if (!el) return;
    const cur = el.value;
    const next = ORDER[ORDER.indexOf(k) + 1];
    let nextVal: string;
    let caret: number;
    let jump = false;
    if (k === "yyyy") {
      const chars = cur.split("");
      if (chars.length >= 4 && pos >= 4) pos = 3;
      if (pos >= chars.length) chars.push(String(d));
      else chars[pos] = String(d);
      nextVal = chars.join("");
      caret = Math.min(pos + 1, nextVal.length);
    } else if (pos === 0 && d > (k === "mm" ? 1 : 3)) {
      nextVal = `0${d}`;
      caret = 2;
      jump = true;
    } else if (pos === 0) {
      const chars = cur.split("");
      if (chars.length === 0) chars.push(String(d));
      else chars[0] = String(d);
      nextVal = chars.join("").slice(0, 2);
      caret = 1;
    } else {
      // pos === 1(§6.1/§7.1 第二位):成段非法 → 拒 digit + feedback,保持原地
      const formed = Number(`${cur.slice(0, 1)}${d}`);
      if (formed === 0 || formed > (k === "mm" ? 12 : 31)) {
        setInvalidSeg(k);
        return;
      }
      nextVal = `${cur.slice(0, 1)}${d}`;
      caret = 2;
      jump = true;
    }
    dirty.current = true;
    setInvalidSeg(null);
    el.value = nextVal;
    setSegs((p) => ({ ...p, [k]: nextVal }));
    selectDigitEl(el, caret);
    if (jump && next) focusSeg(next, true);
  };

  // 年段 Tab:跳出控件进下一个控件(DOM 会先停在段内 📅/× 按钮,手动跳过)
  const focusNextAfterGroup = () => {
    const sel = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]';
    const list = Array.from(document.querySelectorAll<HTMLElement>(sel));
    const start = list.indexOf(rootRef.current ?? document.body);
    for (const el of list.slice(start + 1)) {
      if (!rootRef.current?.contains(el)) {
        el.focus();
        return;
      }
    }
  };

  // 点击段:落点 digit 选中;延时到浏览器放置完 caret 再取 selectionStart
  const onSegClick = (k: keyof Segs, e: React.MouseEvent<HTMLInputElement>) => {
    const el = e.currentTarget;
    window.setTimeout(() => {
      if (document.activeElement === el) selectDigitEl(el, el.selectionStart ?? 0);
    }, 0);
  };

  // insertText 的逐位路径:让 Playwright fill / 输入法候选上屏与真实键入同语义(否则 fill 会把 "01"
  // 追加到预填 "10" 后拼成 "1001");keydown 数字已拦截走 writeDigit,不会双写。
  const onSegBeforeInput = (k: keyof Segs, e: React.FormEvent<HTMLInputElement>) => {
    const ev = e.nativeEvent as InputEvent;
    if (ev.inputType !== "insertText" || !ev.data || !/^[0-9]+$/.test(ev.data)) return;
    e.preventDefault();
    const el = segRefs[k].current;
    if (!el) return;
    let pos = el.selectionStart ?? el.value.length;
    for (const ch of ev.data) {
      writeDigit(k, pos, Number(ch));
      pos = el.selectionStart ?? pos + 1;
    }
  };

  const onSegKeyDown = (k: keyof Segs, e: React.KeyboardEvent<HTMLInputElement>) => {
    const i = ORDER.indexOf(k);
    const el = e.currentTarget;
    const pos = el.selectionStart ?? 0;
    if (/^[0-9]$/.test(e.key)) {
      e.preventDefault();
      writeDigit(k, pos, Number(e.key));
      return;
    }
    if (e.key === "Tab" && !e.shiftKey) {
      e.preventDefault();
      if (k === "yyyy") focusNextAfterGroup();
      else {
        const next = ORDER[i + 1];
        if (next) focusSeg(next, true);
      }
      return;
    }
    if (e.key === "ArrowRight" || e.key === "/") {
      e.preventDefault();
      if (e.key === "/" && pos < segs[k].length) {
        el.setSelectionRange(pos + 1, pos + 1);
      } else if (pos < segs[k].length) {
        selectDigitEl(el, pos + 1);
      } else {
        const next = ORDER[i + 1];
        if (next) focusSeg(next, true);
        else focusNextAfterGroup(); // 年段位尾 → 出控件,同 Tab
      }
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      if (pos > 0) selectDigitEl(el, pos - 1);
      else {
        const prev = ORDER[i - 1];
        if (prev) focusSeg(prev); // 落上段末位 digit
      }
    } else if (e.key === "Backspace" && segs[k] === "" && i > 0) {
      focusSeg(ORDER[i - 1]);
    }
  };

  // 粘贴完整日期分发三段;单段数字走普通入段
  const onSegPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData.getData("text").trim();
    const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(text);
    const mdy = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text);
    if (!iso && !mdy) return;
    e.preventDefault();
    if (iso) {
      setSegs({ yyyy: iso[1], mm: pad2(Number(iso[2])), dd: pad2(Number(iso[3])) });
    } else if (mdy) {
      setSegs({ mm: pad2(Number(mdy[1])), dd: pad2(Number(mdy[2])), yyyy: mdy[3] });
    }
  };

  const pick = (iso: string) => {
    setCalOpen(false);
    setSegs(parseIso(iso));
    if (iso !== value) onChange(iso);
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
    "& .seg-invalid": { color: "error.main" },
  };

  return (
    <Box sx={[{ display: "inline-flex", flexDirection: "column" }, ...(Array.isArray(sx) ? sx : [sx])]}>
      <Box
        ref={rootRef}
        role="group"
        aria-label={name}
        data-value={value}
        sx={[fieldSx, ...(label ? [{ position: "relative" }] : [])]}
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
            setInvalidSeg(null);
          }
        }}
        onClick={() => {
          const ae = document.activeElement;
          if (ae instanceof HTMLElement && rootRef.current?.contains(ae)) return;
          const first = ORDER.find((k) => segs[k].length < MAXLEN[k]) ?? "yyyy";
          focusSeg(first);
        }}
      >
        {label && (
          <Typography
            component="span"
            sx={{
              position: "absolute",
              top: 0,
              transform: "translateY(-50%)",
              left: 10,
              bgcolor: tokens.field,
              px: 0.5,
              fontSize: 11,
              lineHeight: 1.2,
              color: "text.secondary",
            }}
          >
            {label}
          </Typography>
        )}
        <input
          className={`seg${invalidSeg === "mm" ? " seg-invalid" : ""}`}
          aria-invalid={invalidSeg === "mm" || undefined}
          style={{ width: 26 }}
          inputMode="numeric"
          placeholder="mm"
          aria-label={segText.mm}
          value={segs.mm}
          onChange={(e) => onSegInput("mm", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("mm", e)}
          onClick={(e) => onSegClick("mm", e)}
          onPaste={onSegPaste}
          onFocus={onSegFocus}
          onBeforeInput={(e) => onSegBeforeInput("mm", e)}
          ref={segRefs.mm}
        />
        <Box component="span" sx={{ px: 0.25, color: "text.secondary" }}>/</Box>
        <input
          className={`seg${invalidSeg === "dd" ? " seg-invalid" : ""}`}
          aria-invalid={invalidSeg === "dd" || undefined}
          style={{ width: 26 }}
          inputMode="numeric"
          placeholder="dd"
          aria-label={segText.dd}
          value={segs.dd}
          onChange={(e) => onSegInput("dd", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("dd", e)}
          onClick={(e) => onSegClick("dd", e)}
          onPaste={onSegPaste}
          onFocus={onSegFocus}
          onBeforeInput={(e) => onSegBeforeInput("dd", e)}
          ref={segRefs.dd}
        />
        <Box component="span" sx={{ px: 0.25, color: "text.secondary" }}>/</Box>
        <input
          className={`seg${invalidSeg === "yyyy" ? " seg-invalid" : ""}`}
          aria-invalid={invalidSeg === "yyyy" || undefined}
          style={{ width: 44 }}
          inputMode="numeric"
          placeholder="yyyy"
          aria-label={segText.yyyy}
          value={segs.yyyy}
          onChange={(e) => onSegInput("yyyy", e.target.value)}
          onKeyDown={(e) => onSegKeyDown("yyyy", e)}
          onClick={(e) => onSegClick("yyyy", e)}
          onPaste={onSegPaste}
          onFocus={onSegFocus}
          onBeforeInput={(e) => onSegBeforeInput("yyyy", e)}
          ref={segRefs.yyyy}
        />
        <IconButton
          size="small"
          aria-label={`月历 ${name ?? ""}`}
          onClick={(e) => {
            e.stopPropagation();
            setCalOpen(true);
          }}
        >
          <DateRangeIcon fontSize="small" sx={{ color: "text.secondary" }} />
        </IconButton>
        {showClear && (
          <IconButton
            size="small"
            aria-label={`清除 ${name}`}
            onMouseDown={(e) => e.preventDefault()}
            onClick={(e) => {
              e.stopPropagation();
              onChange("");
              setSegs({ mm: "", dd: "", yyyy: "" });
              dirty.current = false;
              setInvalidSeg(null);
              // §14:清空后回焦月段,以今天为基准继续录(显式预填:此处理 value prop 尚为旧值,onSegFocus 不会触发)
              setSegs(todaySegs());
              focusSeg("mm");
            }}
          >
            <ClearIcon fontSize="small" />
          </IconButton>
        )}
      </Box>
      <Popover
        open={calOpen}
        anchorEl={rootRef.current}
        onClose={() => setCalOpen(false)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        transformOrigin={{ vertical: "top", horizontal: "left" }}
      >
        <MiniCalendar initialIso={isoOf(parseIso(value))} onPick={pick} />
      </Popover>
    </Box>
  );
}

// mini 月历(spec 0019 §2.8):‹ › 切月,点日回填+提交+关闭,选中=field 底色,今天=描边
function MiniCalendar({ initialIso, onPick }: { initialIso: string; onPick: (iso: string) => void }) {
  const init = parseIso(initialIso);
  const [ym, setYm] = useState({ y: Number(init.yyyy), m: Number(init.mm) });
  const startDow = new Date(ym.y, ym.m - 1, 1).getDay();
  const days = daysInMonth(ym.y, ym.m);
  const todayIso = isoOf(todaySegs());
  const move = (delta: number) =>
    setYm((p) => {
      const m = p.m + delta;
      return m < 1 ? { y: p.y - 1, m: 12 } : m > 12 ? { y: p.y + 1, m: 1 } : { y: p.y, m };
    });

  return (
    <Box sx={{ p: 1.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", mb: 0.5 }}>
        <IconButton size="small" aria-label={t.date.prevYear} onClick={() => move(-12)}>
          <KeyboardDoubleArrowLeftIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" aria-label={t.date.prevMonth} onClick={() => move(-1)}>
          <ChevronLeftIcon fontSize="small" />
        </IconButton>
        <Box sx={{ flex: 1, textAlign: "center", fontSize: 14 }}>
          {ym.y}.{pad2(ym.m)}
        </Box>
        <IconButton size="small" aria-label={t.date.nextMonth} onClick={() => move(1)}>
          <ChevronRightIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" aria-label={t.date.nextYear} onClick={() => move(12)}>
          <KeyboardDoubleArrowRightIcon fontSize="small" />
        </IconButton>
      </Box>
      <Box
        sx={{ display: "grid", gridTemplateColumns: "repeat(7, 28px)", gap: "2px" }}
        onKeyDown={(e) => {
          // 月历键盘导航(V1 PRD §6.2):←/→/↑/↓ 移 ±1/±7 日格,Enter/Space 走 button 原生选中
          const delta = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[e.key];
          if (!delta) return;
          const cells = Array.from(e.currentTarget.querySelectorAll<HTMLElement>("button[data-iso]"));
          const i = cells.indexOf(document.activeElement as HTMLElement);
          const target = cells[Math.max(0, Math.min(i + delta, cells.length - 1))];
          if (target) {
            e.preventDefault();
            target.focus();
          }
        }}
      >
        {t.date.weekdays.map((w) => (
          <Box key={w} sx={{ textAlign: "center", fontSize: 12, color: "text.secondary", py: 0.5 }}>
            {w}
          </Box>
        ))}
        {Array.from({ length: startDow }).map((_, i) => (
          <Box key={`empty-${i}`} />
        ))}
        {Array.from({ length: days }).map((_, i) => {
          const d = i + 1;
          const iso = `${ym.y}-${pad2(ym.m)}-${pad2(d)}`;
          const sel = iso === initialIso;
          const isToday = iso === todayIso;
          return (
            <Box
              key={iso}
              component="button"
              type="button"
              data-iso={iso}
              onClick={() => onPick(iso)}
              sx={{
                width: 28,
                height: 28,
                textAlign: "center",
                fontSize: 13,
                cursor: "pointer",
                border: "1px solid transparent",
                borderRadius: 1,
                bgcolor: sel ? tokens.field : "transparent",
                ...(isToday ? { borderColor: tokens.border } : {}),
                "&:hover": { bgcolor: tokens.field },
              }}
            >
              {d}
            </Box>
          );
        })}
      </Box>
      <Box sx={{ display: "flex", justifyContent: "center", mt: 0.5 }}>
        <Box
          component="button"
          type="button"
          onClick={() => onPick(todayIso)}
          sx={{ fontSize: 12, color: "text.secondary", cursor: "pointer", border: "none", background: "none", py: 0.5 }}
        >
          {t.date.today}
        </Box>
      </Box>
    </Box>
  );
}
