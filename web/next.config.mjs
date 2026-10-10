// 后端地址:默认本机 API(bootRun 8080);同源代理,浏览器零 CORS
const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  // E2E(prod build)注入 NEXT_DIST_DIR=.next-e2e,与日常 dev 的 .next 互不踩
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${BACKEND}/api/:path*` }];
  },
};

export default nextConfig;
