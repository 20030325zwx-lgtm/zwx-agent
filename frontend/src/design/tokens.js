// Aster 工作台设计令牌：唯一的颜色/尺寸数据源。
// antd 与 CSS 变量都从这里取值，组件样式一律使用 var(--wb-*)，禁止硬编码色值。
// 取值依据 docs/design/2026-11-02-ai-creative-ui-visual-design.md §5：
// 克制、媒体优先、媒体舞台单独配色，页面背景不使用径向渐变。

export const PALETTE = {
  // 创作体验重设计（docs/design/2026-11-02-creative-experience-redesign.md §4.1）：
  // 「安静的编辑部 + 沉浸式创作台」——静暖灰底、石墨主按钮、鸢尾紫强调。
  // 候选值实施时需完成 WCAG 对比度验证（正文 ≥4.5:1，控件边界 ≥3:1）。
  iris: '#6654C7',
  irisDark: '#B8ACFF',
  ink: '#25252A',
  inkDark: '#ECECE7',
  brand: '#2563EB',
  brandHover: '#1D4ED8',
  brandActive: '#1E40AF',
  brandSoft: '#EAF1FF',
  success: '#16803C',
  warning: '#A15C00',
  danger: '#C9364F',
  info: '#2563EB',
  fontFamily:
    "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', sans-serif",
  fontMono: "'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace"
}

// 排版与间距令牌：字号只保留 6 级（规范 §5.3），间距按 4px 网格（§5.4）。
const TYPE = {
  '--wb-fs-page': '24px',
  '--wb-lh-page': '32px',
  '--wb-fs-section': '16px',
  '--wb-lh-section': '24px',
  '--wb-fs-panel': '14px',
  '--wb-lh-panel': '22px',
  '--wb-fs-body': '14px',
  '--wb-lh-body': '22px',
  '--wb-fs-compact': '13px',
  '--wb-lh-compact': '20px',
  '--wb-fs-caption': '12px',
  '--wb-lh-caption': '18px'
}

const SPACE = {
  '--wb-space-1': '4px',
  '--wb-space-2': '8px',
  '--wb-space-3': '12px',
  '--wb-space-4': '16px',
  '--wb-space-5': '20px',
  '--wb-space-6': '24px',
  '--wb-space-7': '32px',
  '--wb-space-8': '40px'
}

// CSS 变量按语义分层：bg（页面底）/ surface（卡片）/ raise（悬浮）/ fill（内嵌填充）/
// border / text / muted / soft（彩色弱底）。深浅两套同步维护。
// --cr-* 为创作体验新语义层（redesign §4.1）：与 --wb-* 并行渐进迁移，不删旧变量。
const CR_LIGHT = {
  '--cr-page-bg': '#F6F5F2',
  '--cr-surface': '#FFFFFF',
  '--cr-surface-2': '#EEEDE9',
  '--cr-text': '#202124',
  '--cr-text-2': '#62646A',
  '--cr-accent': PALETTE.iris,
  '--cr-accent-soft': 'rgba(102, 84, 199, 0.1)',
  '--cr-border': '#E4E3DF',
  '--cr-btn-bg': PALETTE.ink,
  '--cr-btn-fg': '#FFFFFF',
  '--cr-media-stage': '#101113',
  '--cr-radius-ctl': '10px',
  '--cr-radius-media': '14px',
  '--cr-radius-composer': '20px',
  '--cr-radius-pop': '16px'
}

const CR_DARK = {
  '--cr-page-bg': '#151617',
  '--cr-surface': '#202123',
  '--cr-surface-2': '#292B2E',
  '--cr-text': '#F0F0EE',
  '--cr-text-2': '#AAAEB5',
  '--cr-accent': PALETTE.irisDark,
  '--cr-accent-soft': 'rgba(184, 172, 255, 0.14)',
  '--cr-border': '#35373B',
  '--cr-btn-bg': PALETTE.inkDark,
  '--cr-btn-fg': '#17181A',
  '--cr-media-stage': '#0B0C0D',
  '--cr-radius-ctl': '10px',
  '--cr-radius-media': '14px',
  '--cr-radius-composer': '20px',
  '--cr-radius-pop': '16px'
}

// 动效时间 token（redesign §7.1）：统一时长与缓动；退出用 exit 曲线。
// 弹簧参数（stiffness 380/damping 34）按组件调校，不进全局 token。
const CR_MOTION = {
  '--cr-dur-instant': '90ms',
  '--cr-dur-fast': '140ms',
  '--cr-dur-normal': '220ms',
  '--cr-dur-spatial': '320ms',
  '--cr-dur-showcase': '420ms',
  '--cr-ease': 'cubic-bezier(0.22, 1, 0.36, 1)',
  '--cr-ease-exit': 'cubic-bezier(0.4, 0, 1, 1)'
}

export const THEMES = {
  light: {
    css: {
      '--wb-bg': '#F7F7F8',
      '--wb-bg-accent': 'none',
      '--wb-surface': '#FFFFFF',
      '--wb-surface-2': '#FAFAFB',
      '--wb-raise': '#FFFFFF',
      '--wb-fill': '#F0F1F3',
      '--wb-fill-hover': '#E9EBEF',
      '--wb-border': '#E3E4E7',
      '--wb-border-strong': '#CDCFD4',
      '--wb-text': '#18191C',
      '--wb-text-2': '#5E626B',
      '--wb-muted': '#8D919A',
      '--wb-brand': PALETTE.brand,
      '--wb-brand-hover': PALETTE.brandHover,
      '--wb-brand-active': PALETTE.brandActive,
      '--wb-brand-soft': PALETTE.brandSoft,
      '--wb-brand-contrast': '#FFFFFF',
      '--wb-mark-bg': '#EDEFF9',
      '--wb-mark-fg': '#3A3D7D',
      '--wb-mark-ring': 'rgba(58, 61, 125, 0.10)',
      '--wb-success': PALETTE.success,
      '--wb-warning': PALETTE.warning,
      '--wb-danger': PALETTE.danger,
      '--wb-info': PALETTE.info,
      // 阴影只用于浮层（规范 §5.6）；普通卡片用 1px 边框
      '--wb-shadow-sm': '0 1px 2px rgba(0, 0, 0, 0.04)',
      '--wb-shadow-md': '0 8px 24px rgba(0, 0, 0, 0.12)',
      '--wb-shadow-lg': '0 18px 48px rgba(0, 0, 0, 0.18)',
      '--wb-radius-sm': '6px',
      '--wb-radius': '8px',
      '--wb-radius-lg': '10px',
      '--wb-rail': '#F1F1F3',
      '--wb-media-bg': '#0B0C0E',
      '--wb-focus-ring': PALETTE.brand,
      '--wb-scrollbar': 'rgba(60, 60, 80, 0.28)',
      ...TYPE,
      ...SPACE,
      ...CR_LIGHT,
      ...CR_MOTION
    },
    antd: {
      token: {
        colorPrimary: PALETTE.brand,
        colorInfo: PALETTE.info,
        colorSuccess: PALETTE.success,
        colorWarning: PALETTE.warning,
        colorError: PALETTE.danger,
        colorBgLayout: '#F7F7F8',
        colorBgContainer: '#FFFFFF',
        colorBorder: '#E3E4E7',
        colorBorderSecondary: '#EDEEF0',
        colorText: '#18191C',
        colorTextSecondary: '#5E626B',
        colorTextTertiary: '#8D919A',
        borderRadius: 8,
        fontFamily: PALETTE.fontFamily
      }
    }
  },
  dark: {
    css: {
      '--wb-bg': '#111214',
      '--wb-bg-accent': 'none',
      '--wb-surface': '#1C1E22',
      '--wb-surface-2': '#222429',
      '--wb-raise': '#222429',
      '--wb-fill': '#292C31',
      '--wb-fill-hover': '#31353B',
      '--wb-border': '#30333A',
      '--wb-border-strong': '#444850',
      '--wb-text': '#F1F2F4',
      '--wb-text-2': '#B4B7BE',
      '--wb-muted': '#7F838C',
      '--wb-brand': '#6B93FF',
      '--wb-brand-hover': '#8AA9FF',
      '--wb-brand-active': '#5882F0',
      '--wb-brand-soft': 'rgba(107, 147, 255, 0.16)',
      '--wb-brand-contrast': '#FFFFFF',
      '--wb-mark-bg': '#E4E6F5',
      '--wb-mark-fg': '#2F3168',
      '--wb-mark-ring': 'rgba(47, 49, 104, 0.18)',
      '--wb-success': '#3DBE6C',
      '--wb-warning': '#E0A24A',
      '--wb-danger': '#F16580',
      '--wb-info': '#6B93FF',
      '--wb-shadow-sm': '0 1px 2px rgba(0, 0, 0, 0.4)',
      '--wb-shadow-md': '0 8px 24px rgba(0, 0, 0, 0.5)',
      '--wb-shadow-lg': '0 18px 48px rgba(0, 0, 0, 0.6)',
      '--wb-radius-sm': '6px',
      '--wb-radius': '8px',
      '--wb-radius-lg': '10px',
      '--wb-rail': '#17181B',
      '--wb-media-bg': '#0B0C0E',
      '--wb-focus-ring': '#6B93FF',
      '--wb-scrollbar': 'rgba(255, 255, 255, 0.22)',
      ...TYPE,
      ...SPACE,
      ...CR_DARK,
      ...CR_MOTION
    },
    antd: {
      token: {
        colorPrimary: '#6B93FF',
        colorInfo: '#6B93FF',
        colorSuccess: '#3DBE6C',
        colorWarning: '#E0A24A',
        colorError: '#F16580',
        colorBgLayout: '#111214',
        colorBgContainer: '#1C1E22',
        colorBgElevated: '#222429',
        colorBorder: '#444850',
        colorBorderSecondary: '#30333A',
        colorText: '#F1F2F4',
        colorTextSecondary: '#B4B7BE',
        colorTextTertiary: '#7F838C',
        borderRadius: 8,
        fontFamily: PALETTE.fontFamily
      }
    }
  }
}
