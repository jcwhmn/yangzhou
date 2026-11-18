# spec 0145 — YPJ-237 甘特标题列分割线可拖拽

> 状态:待用户过目(三件套齐全后编码)。票面核对 pending(yz session 过期,开工时补核:票面范围与本 spec 需求一致才动工,不一致先问)。

## 一、需求

**用户原话(2026-11-19)**:「把两列之间的分割线改成可以拖拽左右移动的」。

- 甘特页标题列与甘特区之间的分割线变为拖拽手柄:左右拖 → 标题列宽度实时变化,甘特区相应伸缩。
- 宽度记忆:拖完写 localStorage,刷新/下次进入保持;无存储或越界值回落 280(236 出厂宽)。
- **范围外(非目标)**:触摸端拖拽(桌面鼠标为准);hover 联动(236 已拍板非目标);分割线拖拽影响纵向滚动对齐的语义(行对齐由 flex 布局天然保证,不加新逻辑)。

## 二、详细设计

### 现状

- `TITLE_W = 280`(page.tsx:42,常量单点定义、标题面板单点使用 :680)。
- 视觉分割线 = 标题面板 `borderRight: 1px solid divider`(236 手测用例 3 已验,不得变样)。

### 改法(单文件 `web/app/p/[key]/gantt/page.tsx`)

1. `TITLE_W` 常量改为默认值语义,新增 state:

```tsx
const TITLE_W_DEFAULT = 280;
const [titleW, setTitleW] = useState(() => readTitleW()); // localStorage "yz-gantt-title-w",越界夹回
```

2. 标题面板 `width: TITLE_W` → `width: titleW`;`borderRight` **保留原样**(视觉零变化)。
3. 标题面板上叠一个绝对定位热区(宽 9px,骑在右边线上,`left: -5`),`cursor: col-resize`;hover/拖拽中显示 `info.main` 竖线(2px),否则透明。`data-testid="gantt-splitter"`。
4. 拖拽:`onMouseDown` 记 startX/startW,挂 window `mousemove`/`mouseup`(拖拽态 ref 防抖);move 中 `setTitleW(clamp(startW + dx, 120, maxW))`,`maxW = Math.max(120, Math.floor(containerW / 2))`;up 时写 localStorage、解绑。
5. 双击热区 → 重置 280 + 写 localStorage。
6. 键盘可达:热区 `role="separator"` + `tabIndex=0` + `aria-label`,ArrowLeft/Right ±16px 并落库。

否决备选:独立 5px 彩色分割条元素(改变已验证视觉,弃);MUI Resizer 类组件(无此依赖,引包不值);拖拽中 throttle setState(240Hz 下 60 行列表 re-render 可承受,实测卡再优化)。

### 操作流程(状态×动作真值表)

| 状态 | 动作 | 行为 |
|---|---|---|
| 初始(有合法存储值) | 进页面 | 标题列 = 存储宽 |
| 初始(无存储/值越界) | 进页面 | 标题列 = 280 |
| 悬停热区 | 鼠标移入 | cursor col-resize;2px info.main 竖线显现 |
| 按下热区 | mousedown | 进入拖拽态(竖线保持显现) |
| 拖拽态 | mousemove Δx | width=clamp(startW+Δx,120,containerW/2);两列实时伸缩,行对齐不变 |
| 拖拽态 | mouseup | 写 localStorage,退出拖拽态,竖线隐去 |
| 非拖拽态 | 双击热区 | width=280,写 localStorage |
| 非拖拽态 | 键盘焦点+←/→ | ±16px,写 localStorage |
| 拖拽中 | 甘特区横滚/缩放 | 照常,互不干扰 |

### 页面示意图(贴合实况)

```text
┌────────────────┃┃──────────────────────────────┐
│ 标题列 grey.50  ┃┃ 甘特区(横滚/缩放)             │
│ (自然宽标题)    ┃┃   ▬▬▬▬▬▬   ▬▬▬               │
│                ┃┃   ▬▬▬                        │
└────────────────┃┃──────────────────────────────┘
            ↑ 1px 右边线(236 原样保留)
              + 9px 透明热区骑线上;hover/拖拽现 2px info.main
```

### e2e 影响

- 存量用例不引用 280 硬值(仅 `gantt-titlepane` testid),不破坏。
- 新增一例(追加 gantt.spec):拖 splitter → `gantt-titlepane` 宽度变化且 ≠280;reload 后保持。断言用相对变化,不写死像素。

## 三、手工测试用例(rebuild prod 后)

1. 拖分割线左右:标题列宽实时变,甘特区伸缩,行对齐不乱(逐行扫);右侧线样式不变。
2. 拖到极左/极右:夹在 120 / 容器半宽,松手不弹。
3. 刷新页面:宽度保持;双击分割线:回 280。
4. 键盘:Tab 到分割线,←/→ 微调。
5. 回归:236 四条(独立横滚/反向不动/异色底/短标题铺满)+230 一日窗不回归。
