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
};
