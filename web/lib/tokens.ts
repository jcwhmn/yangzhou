// 设计令牌基线(spec 0017 / YPJ-17):清爽、淡背景、黑字、细线、紧凑、平面层次。
// 背景已定稿:淡蓝(TC1 两版截图走查用户拍板;候选淡绿 #f2f8f4 落选,记录见 spec 0017 As-built)。
export const tokens = {
  bg: "#f4f8fb",
  // 控件底色(无边框方案的标记手段;比 bg 深一档,白纸上也可见)
  field: "#e9f0f6",
  paper: "#ffffff",
  divider: "#dde5ec",
  border: "#c5d3e0",
  // 近黑文字:淡背景上保证对比度
  text: "#1c1b1f",
  borderWidth: 1,
  radius: 8,
  // 紧凑:间距单位 8 → 7(不启用全局 density,避免压行高)
  spacing: 7,
  // 语义色(V65 走查收敛:单一真相源,Providers/Verdict 共引;域色语义见 DOMAIN.md 绿黄红)
  primary: "#2E6FAF", // 钢蓝,与 bg 同族;替代 MUI 出厂蓝 #1976d2
  success: "#2e7d32",
  warning: "#ed6c02",
  error: "#c62828",
  gap: "#b45309", // 判定「差级」
  neutral: "#9e9e9e", // 判定「有余」/指派人兜底
  accent: "#7c4dff", // 判定「未评级」
};
