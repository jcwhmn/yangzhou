# 部署:GitHub webhook 配置(yz 宿主机)

> 对应 spec 0073(YPJ-85)。目标:GitHub push / pull_request 事件秒级触发 yz 的 GitHub 同步。
> 适用任何部署形态(本机常驻 / VPS),按宿主机网络条件三选一。

## 0. 前置(所有场景共同)

### yz 侧

1. yz API 运行中(端口以 8081 为例),启动时注入 webhook secret:

   ```powershell
   # PowerShell
   $env:YANGZHOU_GITHUB_WEBHOOK_SECRET = "<48位随机hex>"
   gradle :api:bootRun --args='--server.port=8081'
   ```

   ```bash
   # Linux
   YANGZHOU_GITHUB_WEBHOOK_SECRET="<48位随机hex>" java -jar yz-api.jar --server.port=8081
   ```

   - secret 生成:`openssl rand -hex 24` 或 PowerShell `-join ((1..48) | %% {'{0:x}' -f (Get-Random -Max 16)})`
   - **未配置/为空 → webhook 端点一律 403 全拒**(安全默认,不会裸奔)
   - secret 分三处保存:宿主机 env / 密码管理器 / GitHub webhook 配置页;永不入仓

2. GitHub 同步 PAT 已配置(workspace 的 github-token)且项目已挂载仓库(`project_repos.repo` = `owner/name`)。

### GitHub 侧

```bash
gh api repos/<owner>/<repo>/hooks --input - --method POST <<'EOF'
{
  "name": "web",
  "active": true,
  "events": ["push", "pull_request"],
  "config": {
    "url": "<回调URL>/api/github/webhook",
    "content_type": "json",
    "secret": "<同一份secret>",
    "insecure_ssl": "0"
  }
}
EOF
```

- 创建后 GitHub 自动发 **ping**,`gh api repos/<owner>/<repo>/hooks/<id>/deliveries` 看到 `status_code: 200` 即链路通(ping 在 yz 侧走「未知事件 200 ignored」路径,属预期)
- 失败投递可重发:`gh api repos/<owner>/<repo>/hooks/<id>/deliveries/<delivery-id>/redeliveries -X POST`

### 回调 URL 的硬约束

- **GitHub webhook 只回调 80 / 443 端口**。yz 若跑在 8081,前置必须有 80/443 入口(反代或隧道),GitHub 不会直连 8081。
- 回调总耗时必须 **< 10s**(实测,yz 端点是异步的,命中即回 202,正常远低于此)。

---

## 场景 A:有独立固定公网 IP(国内服务器)

国内服务器被 GitHub 主动回调是**入站**流量,公网 IP 可达即通,不涉及出境,稳定性好。

### A1. 直接用 IP + 80 端口(最简)

```bash
gh api ... config.url = "http://<公网IP>/api/github/webhook"
```

- 宿主机 80 → 反代到 8081(nginx `proxy_pass` 或 Caddy 一行),或 yz 直接监听 80(不推荐,需提权)
- 用 IP 不用域名则**不需要 ICP 备案**
- 明文 HTTP 的安全性:webhook 有 HMAC 签名校验(篡改/伪造被 403),payload 只含分支/PR 元数据,无敏感内容——自用可接受;介意明文走 A2

### A2. 域名 + HTTPS(更规范)

1. 域名解析 A 记录到固定 IP;**服务器在中国大陆则域名必须 ICP 备案**(80/443 未备案会被运营商拦截)
2. Caddy 最省事(自动签发续期 Let's Encrypt):

   ```
   yz.example.com {
       reverse_proxy localhost:8081
   }
   ```

3. 回调 URL 用 `https://yz.example.com/api/github/webhook`

---

## 场景 B:无独立固定 IP

思路:让一个**有稳定公网地址的第三方**接收 GitHub 回调,隧道转发到本机 yz。

### B1. Tailscale Funnel(本项目实测方案,推荐起步)

- 免费固定域名,一次配置长期有效,重启自动恢复:

  ```bash
  tailscale funnel --bg 8081
  # 得到 https://<机器名>.<tailnet>.ts.net/ → 127.0.0.1:8081
  ```

- 回调 URL:`https://<机器名>.<tailnet>.ts.net/api/github/webhook`(443,Tailscale 边缘终结 TLS)
- 国内可达性:入站经 Tailscale 边缘节点,一般可用,偶发慢;webhook 超时 10s,若频繁超时换 B2

### B2. Cloudflare Tunnel

- 免费固定域名(`xxx.trycloudflare.com` 或自有域名),国内到 CF 边缘通常可达:

  ```bash
  cloudflared tunnel --url http://localhost:8081
  ```

- 回调 URL 同理换 CF 域名;自有域名接入 CF 不受国内备案约束(CF 边缘在境外)

### B3. frp 自建(最稳,但回到场景 A)

- 需要一台有公网 IP 的国内 VPS 跑 frps,本机 frpc 转发 8081;VPS 侧再按 A1/A2 暴露 80/443
- 国内 GitHub→VPS 入站直连,延迟最低、最不易超时;适合长期正式部署

### B4. 不推荐

- **ngrok 免费版**:域名随机重启即变,webhook 要重配;其域名在国内偶发不可达
- **DDNS + 路由器端口映射**:家宽 80/443 常被运营商封禁,且 GitHub 侧 DNS 缓存会拖住切换,不作方案

---

## 排错速查

| 症状 | 含义 | 处置 |
|---|---|---|
| deliveries 里 ping = 403 | secret 不一致,或 yz 未配 secret | 两边核对 env 与 hook 配置 |
| ping 都收不到 | URL/端口/防火墙问题 | 回调 URL 必须 80/443;查反代与入站规则 |
| push = 500 且 status 含 `context deadline exceeded` | GitHub 等响应超 10s | 确认 yz ≥ 含 YPJ-85 追加修复(异步 202)的版本 |
| 200 ignored | 事件不在 push/pull_request,或 repo 未挂载 | 正常;要同步先在 yz 挂载仓库 |
| 同步没发生但 202 | workspace 未配 PAT(yz 日志有 WARN) | 配置 workspace github-token |
