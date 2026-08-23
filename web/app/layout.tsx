import { CssBaseline } from "@mui/material";
import type { Metadata } from "next";
import { Providers } from "@/components/Providers";

export const metadata: Metadata = { title: "扬州 yangzhou" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <Providers>
          <CssBaseline />
          {children}
        </Providers>
      </body>
    </html>
  );
}
