"use client";

import { AppRouterCacheProvider } from "@mui/material-nextjs/v15-appRouter";
import { ThemeProvider, createTheme } from "@mui/material";

const theme = createTheme({
  typography: {
    fontFamily: 'system-ui, "Segoe UI", "Microsoft YaHei", sans-serif',
  },
  components: {
    // V11-Q3:全局去边框(standard 无 outlined 边框,视觉干净)
    MuiTextField: { defaultProps: { variant: "standard" } },
    MuiFormControl: { defaultProps: { variant: "standard" } },
  },
});

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <AppRouterCacheProvider options={{ key: "mui" }}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </AppRouterCacheProvider>
  );
}
