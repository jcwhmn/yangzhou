// API 不可达状态:模块级单例 pub/sub(模式照 notifications-store;0104)。
// 连接失败(重试耗尽)→ setDown;任一请求拿到响应 → setUp 自愈,无需轮询。
type Listener = (down: boolean) => void;

let down = false;
const listeners = new Set<Listener>();

function set(v: boolean) {
  if (v === down) return;
  down = v;
  listeners.forEach((l) => l(down));
}

export function setDown(): void {
  set(true);
}

export function setUp(): void {
  set(false);
}

export function subscribeConnection(l: Listener): () => void {
  listeners.add(l);
  l(down);
  return () => {
    listeners.delete(l);
  };
}
