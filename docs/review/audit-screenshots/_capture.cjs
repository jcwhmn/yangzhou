// 审美盘点截图采集(只读):对 localhost:3000 逐页截 1440x900 / 390x844 两视口。
// 服务器已由用户在跑(dev 或 prod),本脚本不启停任何服务。产物:audit-screenshots/*.png + computed-colors.json
const { chromium } = require("D:/code/yangzhou/web/node_modules/@playwright/test");
const fs = require("fs");
const path = require("path");

const BASE = "http://localhost:3000";
const OUT = path.join(__dirname);
const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

const slug = (r) => r.replace(/^\/+/, "").replace(/[/?=&]/g, "_").replace(/_+/g, "_") || "_home";

async function settle(page) {
  await page.waitForLoadState("networkidle", { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(1200); // hydration + 数据渲染
}

async function shoot(page, route, tag) {
  await page.goto(BASE + route, { waitUntil: "load", timeout: 90000 });
  await settle(page);
  await page.screenshot({ path: path.join(OUT, `${tag}.desktop.png`), fullPage: true });
  await page.setViewportSize(MOBILE);
  await settle(page);
  await page.screenshot({ path: path.join(OUT, `${tag}.mobile.png`), fullPage: true });
  await page.setViewportSize(DESKTOP);
  console.log("ok  " + tag);
}

async function computedColors(page) {
  return page.evaluate(() => {
    const counts = {};
    for (const el of document.querySelectorAll("*")) {
      const cs = getComputedStyle(el);
      for (const prop of ["color", "backgroundColor", "borderBottomColor"]) {
        const v = cs[prop];
        if (v && v !== "rgba(0, 0, 0, 0)") counts[v] = (counts[v] || 0) + 1;
      }
    }
    return counts;
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();

  // 0) 未登录 login 页 + 经前端代理拿 token(与实际接线后端匹配)
  const anon = await browser.newContext({ viewport: DESKTOP });
  const p0 = await anon.newPage();
  await p0.goto(BASE + "/login", { waitUntil: "load", timeout: 90000 });
  await settle(p0);
  await p0.screenshot({ path: path.join(OUT, "_login.desktop.png"), fullPage: true });
  await p0.setViewportSize(MOBILE);
  await p0.screenshot({ path: path.join(OUT, "_login.mobile.png"), fullPage: true });
  await p0.setViewportSize(DESKTOP);
  console.log("ok  _login");
  const login = await p0.request.post(BASE + "/api/auth/login", {
    data: { username: "me", password: "secret" },
  });
  if (!login.ok()) throw new Error("login failed " + login.status());
  const { token } = await login.json();
  const auth = { headers: { Authorization: `Bearer ${token}` } };

  // 1) 选内容最多的项目(截图要有真实数据)
  const projects = await p0.request.get(BASE + "/api/projects", auth).then((r) => r.json());
  let pick = null;
  for (const prj of projects) {
    try {
      const b = await p0.request
        .get(BASE + `/api/projects/${prj.key}/board`, auth)
        .then((r) => (r.ok() ? r.json() : null));
      const n = b ? (b.items || []).length : -1;
      if (!pick || n > pick.n) pick = { key: prj.key, n, board: b };
    } catch {}
  }
  if (!pick) throw new Error("no project reachable");
  const key = pick.key;
  const sprints = await p0.request
    .get(BASE + `/api/projects/${key}/sprints`, auth)
    .then((r) => (r.ok() ? r.json() : []))
    .catch(() => []);
  const items = (pick.board && pick.board.items) || [];
  const itemId = items.length ? items[0].itemId : null;
  const sprintId = sprints.length ? sprints[0].sprintId : null;
  const q = items.length ? encodeURIComponent(items[0].title.slice(0, 4)) : "test";
  console.log("picked project=%s items=%d sprint=%s", key, items.length, !!sprintId);
  await anon.close();

  // 2) 已登录上下文
  const ctx = await browser.newContext({ viewport: DESKTOP });
  await ctx.addInitScript((t) => localStorage.setItem("yz-token", t), token);
  const page = await ctx.newPage();

  const routes = [
    "/",
    "/notifications",
    "/search?q=" + q,
    "/standup",
    "/members",
    "/capabilities",
    "/attributes",
    "/recycle-bin",
    `/p/${key}`,
    `/p/${key}/overview`,
    `/p/${key}/backlog`,
    `/p/${key}/table`,
    `/p/${key}/gantt`,
    `/p/${key}/time`,
  ];
  if (itemId) routes.push(`/p/${key}/i/${itemId}`);
  if (sprintId) routes.push(`/p/${key}/sprint/${sprintId}`);
  routes.push("/definitely-not-a-route");

  const colorSamples = {};
  for (const r of routes) {
    const tag = slug(r);
    try {
      await shoot(page, r, tag);
      if (["/", `/p/${key}`, `/p/${key}/i/${itemId}`, "/members"].includes(r)) {
        colorSamples[tag === "_home" ? "home" : tag] = await computedColors(page);
      }
    } catch (e) {
      console.log("FAIL " + tag + " :: " + e.message.split("\n")[0]);
    }
  }
  fs.writeFileSync(path.join(OUT, "computed-colors.json"), JSON.stringify(colorSamples, null, 1));
  console.log("done. files:", fs.readdirSync(OUT).length);
  await browser.close();
})();
