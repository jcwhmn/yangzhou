"use client";

import { AppRouterCacheProvider } from "@mui/material-nextjs/v15-appRouter";
import { ThemeProvider, createTheme } from "@mui/material";

const theme = createTheme({
  typography: {
    fontFamily: 'system-ui, "Segoe UI", "Microsoft YaHei", sans-serif',
  },
});

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <AppRouterCacheProvider options={{ key: "mui" }}>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </AppRouterCacheProvider>
  );
}
