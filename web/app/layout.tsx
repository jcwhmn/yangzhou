import { CssBaseline } from "@mui/material";
import type { Metadata } from "next";
import { Providers } from "@/components/Providers";
import { AppNav } from "@/components/AppNavClient";
import { Footer } from "@/components/Footer";

export const metadata: Metadata = { title: "扬州 yangzhou" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>
        <Providers>
          <CssBaseline />
          <AppNav />
          {children}
          <Footer />
        </Providers>
      </body>
    </html>
  );
}
