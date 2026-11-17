/**
 * 设计 token（TS 侧）。与 `tokens.css` 保持同一组数值，供 JS/TS 场景读取
 * （画布绘制、图表主题、Story 参数等）。
 *
 * 规则：组件内样式一律使用 CSS 变量（var(--c-brand) 等），不在 .vue 中硬编码色值。
 */
export const colorTokens = {
  /** 主品牌深绿（P1 定稿） */
  brand: '#0A6B5B',
  /** 原型里的亮一档品牌绿，用于渐变/次级强调 */
  brandAlt: '#0A7A66',
  brandSoft: '#E6F4F0',
  brandDeep: '#075E50',
  /** 深色 */
  ink: '#0E1D2B',
  ink2: '#31465A',
  muted: '#6B7F90',
  faint: '#94A6B5',
  line: '#E3E9ED',
  lineSoft: '#EDF1F4',
  /** 浅底 */
  bg: '#F3F7F8',
  bgSoft: '#F8FAFB',
  card: '#FFFFFF',
  ai: '#4F46E5',
  aiSoft: '#EEEDFD',
  blue: '#2563EB',
  blueSoft: '#EAF1FE',
  amber: '#B45309',
  amberSoft: '#FDF2E3',
  red: '#C62828',
  redSoft: '#FCEBEA',
  purple: '#7C3AED',
  purpleSoft: '#F3EDFE',
  success: '#0F9D6E',
  successSoft: '#E8F7F1',
  /** 侧边栏深色渐变三档 */
  navStart: '#0C1E29',
  navMid: '#0A1721',
  navEnd: '#0B1B26',
} as const

/** 4px 基准间距 */
export const spaceTokens = {
  '1': '4px',
  '2': '8px',
  '3': '12px',
  '4': '16px',
  '5': '20px',
  '6': '24px',
  '8': '32px',
  '10': '40px',
  '12': '48px',
} as const

export const radiusTokens = {
  xs: '4px',
  sm: '6px',
  md: '8px',
  lg: '12px',
  xl: '14px',
  '2xl': '18px',
  pill: '999px',
} as const

export const shadowTokens = {
  sm: '0 1px 2px rgba(14, 29, 43, 0.04)',
  md: '0 1px 2px rgba(14, 29, 43, 0.04), 0 6px 20px rgba(14, 29, 43, 0.05)',
  lg: '0 18px 50px rgba(14, 29, 43, 0.13)',
  ring: '0 0 0 3px rgba(10, 107, 91, 0.28)',
} as const

export const fontTokens = {
  sans: '"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Segoe UI", system-ui, sans-serif',
  mono: '"Cascadia Code", "Consolas", "SFMono-Regular", monospace',
  size: {
    '2xs': '10px',
    xs: '10.5px',
    sm: '11.5px',
    base: '13px',
    md: '14px',
    lg: '16px',
    xl: '19px',
    '2xl': '24px',
  },
  weight: { regular: '400', medium: '500', semibold: '600', bold: '700' },
  lineHeight: { tight: '1.25', base: '1.5', loose: '1.7' },
} as const

export const layoutTokens = {
  sidebarWidth: '236px',
  sidebarWidthCollapsed: '64px',
  topbarHeight: '56px',
  stagePadding: '18px 22px 24px',
  contentMaxWidth: '1440px',
  copilotRailWidth: '208px',
} as const

export const motionTokens = {
  fast: '140ms',
  base: '200ms',
  easing: 'cubic-bezier(0.4, 0, 0.2, 1)',
} as const

export const zIndexTokens = {
  nav: 10,
  dropdown: 50,
  overlay: 100,
  modal: 110,
  toast: 200,
} as const

export const breakpoints = {
  sm: 768,
  md: 1024,
  lg: 1280,
  xl: 1440,
} as const

export const designTokens = {
  color: colorTokens,
  space: spaceTokens,
  radius: radiusTokens,
  shadow: shadowTokens,
  font: fontTokens,
  layout: layoutTokens,
  motion: motionTokens,
  z: zIndexTokens,
  breakpoints,
} as const

export type DesignTokens = typeof designTokens
