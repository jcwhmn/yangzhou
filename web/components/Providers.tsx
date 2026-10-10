"use client";

import { AppRouterCacheProvider } from "@mui/material-nextjs/v15-appRouter";
import { ThemeProvider, createTheme } from "@mui/material";
import { tokens } from "@/lib/tokens";

// theme 基线(spec 0017):令牌在 web/lib/tokens.ts;此处只做组装。
// 层次 = 平面 + 细分割线 + 卡片描边,不用重阴影;输入框全局 outlined(反转 V11-Q3 的 standard,下划线全灭)。
const theme = createTheme({
  spacing: tokens.spacing,
  shape: { borderRadius: tokens.radius },
  palette: {
    text: { primary: tokens.text },
    background: { default: tokens.bg, paper: tokens.paper },
    divider: tokens.divider,
  },
  typography: {
    fontFamily: 'system-ui, "Segoe UI", "Microsoft YaHei", sans-serif',
  },
  components: {
    MuiTextField: { defaultProps: { variant: "outlined" } },
    MuiFormControl: { defaultProps: { variant: "outlined" } },
    // 无边框控件(2026-10-10 用户走查反馈):静止态仅靠底色标记,聚焦时显边框做反馈
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          backgroundColor: tokens.field,
          "&:hover": { backgroundColor: tokens.field },
          "&.Mui-focused": {
            backgroundColor: tokens.paper,
            "& .MuiOutlinedInput-notchedOutline": { borderColor: tokens.border },
          },
        },
        notchedOutline: { borderColor: "transparent" },
      },
    },
    // 平面卡片:描边代替阴影
    MuiPaper: { defaultProps: { elevation: 0, variant: "outlined" } },
    MuiButton: { defaultProps: { disableElevation: true } },
  },
});

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <AppRouterCacheProvider options={{ key: "mui" }}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </AppRouterCacheProvider>
  );
}
