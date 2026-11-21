"use client";

import { Box, Button, Chip, Stack, TextField, Typography } from "@mui/material";
import { t } from "@/lib/texts";
import { type SuggestedEntry, type TimeEntry } from "../types";

/** TimeLogTab(YPJ-251 自 page.tsx 搬出;timeLog/suggestions 群态留 page(首屏加载/跨页签存活),动作回调上行,runningEntry 派生内置) */
export function TimeLogTab({
  timeLog,

  manualMinutes,

  manualNote,

  onManualMinutesChange,

  onManualNoteChange,

  suggestions,

  suggestionsBusy,

  onStartTimer,

  onStopTimer,

  onAddManual,

  onLoadSuggestions,

  onLogSuggestion,
}: {
  timeLog: { entries: TimeEntry[]; totalMinutes: number } | null;

  manualMinutes: string;

  manualNote: string;

  onManualMinutesChange: (v: string) => void;

  onManualNoteChange: (v: string) => void;

  suggestions: SuggestedEntry[] | null;

  suggestionsBusy: boolean;

  onStartTimer: () => void;

  onStopTimer: (entryId: string) => void;

  onAddManual: () => void;

  onLoadSuggestions: () => void;

  onLogSuggestion: (s: SuggestedEntry) => void;
}) {
  const runningEntry = timeLog?.entries.find((e) => e.endedAt === null) ?? null;

  return (
    <Box>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h6">{t.time.section}</Typography>

        {timeLog && timeLog.totalMinutes > 0 && (
          <Typography variant="body2" color="text.secondary">
            {t.time.fmt(timeLog.totalMinutes)}
          </Typography>
        )}

        {runningEntry ? (
          <Chip
            size="small"
            color="warning"
            label={`${t.time.running} ${t.time.fmt(timeLog!.entries.find((e) => e.endedAt === null)!.minutes)}`}
          />
        ) : (
          <Button size="small" variant="outlined" onClick={onStartTimer}>
            {t.time.start}
          </Button>
        )}
      </Stack>

      {runningEntry && (
        <Button
          size="small"
          color="error"
          variant="contained"
          onClick={() => onStopTimer(runningEntry.timeEntryId)}
          sx={{ mb: 1 }}
        >
          {t.time.stop}
        </Button>
      )}

      {timeLog && timeLog.entries.length > 0 && (
        <Stack spacing={0.5} sx={{ mb: 1 }}>
          {timeLog.entries.map((e) => (
            <Stack
              key={e.timeEntryId}
              direction="row"
              spacing={1}
              alignItems="center"
            >
              <Typography variant="caption" color="text.secondary">
                {e.member} · {new Date(e.startedAt).toLocaleString("zh-CN")}
                {e.endedAt
                  ? ` → ${new Date(e.endedAt).toLocaleTimeString("zh-CN")}`
                  : ` → ${t.time.running}`}
              </Typography>

              <Typography variant="caption">{t.time.fmt(e.minutes)}</Typography>

              {e.note && (
                <Typography variant="caption" color="text.secondary">
                  {e.note}
                </Typography>
              )}

              {e.endedAt === null && (
                <Button
                  size="small"
                  color="error"
                  onClick={() => onStopTimer(e.timeEntryId)}
                >
                  {t.time.stop}
                </Button>
              )}
            </Stack>
          ))}
        </Stack>
      )}

      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
        <TextField
          size="small"

          label={t.time.minutesLabel}

          type="number"

          value={manualMinutes}

          onChange={(e) => onManualMinutesChange(e.target.value)}

          sx={{ width: 140 }}
        />

        <TextField
          size="small"

          label={t.time.noteLabel}

          value={manualNote}

          onChange={(e) => onManualNoteChange(e.target.value)}

          sx={{ width: 220 }}
        />

        <Button
          size="small"
          variant="outlined"
          onClick={onAddManual}
          disabled={!manualMinutes}
        >
          {t.time.addManual}
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
        <Button
          size="small"
          variant="outlined"
          onClick={onLoadSuggestions}
          disabled={suggestionsBusy}
        >
          {t.time.suggest}
        </Button>
      </Stack>

      {suggestions !== null && suggestions.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          {t.time.suggestEmpty}
        </Typography>
      )}

      {(suggestions ?? []).map((s) => (
        <Stack
          key={s.ref}
          direction="row"
          spacing={1}
          alignItems="center"
          sx={{ mt: 0.5 }}
        >
          <Typography variant="caption" color="text.secondary">
            {s.ref} · {s.commitCount} commits ·{" "}
            {new Date(s.startedAt).toLocaleString("zh-CN")} →{" "}
            {new Date(s.endedAt).toLocaleTimeString("zh-CN")}
          </Typography>

          <Button
            size="small"
            variant="outlined"
            onClick={() => onLogSuggestion(s)}
            disabled={suggestionsBusy}
          >
            {t.time.logSuggestion}
          </Button>
        </Stack>
      ))}
    </Box>
  );
}
