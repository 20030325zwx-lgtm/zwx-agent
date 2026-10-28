// Aster 工作台设计令牌：唯一的颜色/尺寸数据源。
// antd 与 CSS 变量都从这里取值，组件样式一律使用 var(--wb-*)，禁止硬编码色值。

export const PALETTE = {
  brand: '#2E6BE6',
  brandHover: '#2458C8',
  brandActive: '#1E48AC',
  brandSoft: 'rgba(46, 107, 230, 0.10)',
  brandGradient: 'linear-gradient(135deg, #2E6BE6 0%, #1FA2D6 60%, #22C3BE 100%)',
  success: '#1F9D4D',
  warning: '#B45309',
  danger: '#DC2F4C',
  info: '#2590FF',
  fontFamily:
    "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif",
  fontMono: "'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace"
}

// CSS 变量按语义分层：bg（页面底）/ surface（卡片）/ raise（悬浮）/ fill（内嵌填充）/
// border / text / muted / soft（彩色弱底）。深浅两套同步维护。
export const THEMES = {
  light: {
    css: {
      '--wb-bg': '#F5F6FA',
      '--wb-bg-accent': 'radial-gradient(1200px 500px at 85% -10%, rgba(46,107,230,0.07), transparent 60%)',
      '--wb-surface': '#FFFFFF',
      '--wb-surface-2': '#FAFAFE',
      '--wb-raise': '#FFFFFF',
      '--wb-fill': 'rgba(20, 22, 40, 0.045)',
      '--wb-fill-hover': 'rgba(20, 22, 40, 0.08)',
      '--wb-border': '#E5E6F0',
      '--wb-border-strong': '#D4D6E4',
      '--wb-text': '#191B2A',
      '--wb-text-2': '#565C74',
      '--wb-muted': '#8A90A6',
      '--wb-brand': PALETTE.brand,
      '--wb-brand-hover': PALETTE.brandHover,
      '--wb-brand-active': PALETTE.brandActive,
      '--wb-brand-soft': PALETTE.brandSoft,
      '--wb-brand-contrast': '#FFFFFF',
      '--wb-success': PALETTE.success,
      '--wb-warning': PALETTE.warning,
      '--wb-danger': PALETTE.danger,
      '--wb-info': PALETTE.info,
      '--wb-shadow-sm': '0 1px 2px rgba(25, 27, 42, 0.05)',
      '--wb-shadow-md': '0 4px 16px rgba(25, 27, 42, 0.07), 0 1px 3px rgba(25, 27, 42, 0.05)',
      '--wb-shadow-lg': '0 12px 40px rgba(25, 27, 42, 0.12)',
      '--wb-radius-sm': '8px',
      '--wb-radius': '12px',
      '--wb-radius-lg': '16px',
      '--wb-rail': '#FFFFFF',
      '--wb-scrollbar': 'rgba(60, 60, 80, 0.28)'
    },
    antd: {
      token: {
        colorPrimary: PALETTE.brand,
        colorInfo: PALETTE.info,
        colorSuccess: PALETTE.success,
        colorWarning: PALETTE.warning,
        colorError: PALETTE.danger,
        colorBgLayout: '#F5F6FA',
        colorBgContainer: '#FFFFFF',
        colorBorder: '#E5E6F0',
        colorBorderSecondary: '#EEF0F6',
        colorText: '#191B2A',
        colorTextSecondary: '#565C74',
        colorTextTertiary: '#8A90A6',
        borderRadius: 10,
        fontFamily: PALETTE.fontFamily
      }
    }
  },
  dark: {
    css: {
      '--wb-bg': '#0F111A',
      '--wb-bg-accent': 'radial-gradient(1200px 500px at 85% -10%, rgba(92,141,246,0.1), transparent 60%)',
      '--wb-surface': '#161927',
      '--wb-surface-2': '#1B1F30',
      '--wb-raise': '#1E2234',
      '--wb-fill': 'rgba(255, 255, 255, 0.06)',
      '--wb-fill-hover': 'rgba(255, 255, 255, 0.1)',
      '--wb-border': '#262B3E',
      '--wb-border-strong': '#333950',
      '--wb-text': '#E9EBF5',
      '--wb-text-2': '#A9AEC4',
      '--wb-muted': '#71778E',
      '--wb-brand': '#5C8DF6',
      '--wb-brand-hover': '#7BA2FF',
      '--wb-brand-active': '#4A76E8',
      '--wb-brand-soft': 'rgba(92, 141, 246, 0.16)',
      '--wb-brand-contrast': '#FFFFFF',
      '--wb-success': '#3DBE6C',
      '--wb-warning': '#E5A23C',
      '--wb-danger': '#F16580',
      '--wb-info': '#5AA7FF',
      '--wb-shadow-sm': '0 1px 2px rgba(0, 0, 0, 0.4)',
      '--wb-shadow-md': '0 4px 16px rgba(0, 0, 0, 0.45)',
      '--wb-shadow-lg': '0 12px 40px rgba(0, 0, 0, 0.55)',
      '--wb-radius-sm': '8px',
      '--wb-radius': '12px',
      '--wb-radius-lg': '16px',
      '--wb-rail': '#141726',
      '--wb-scrollbar': 'rgba(255, 255, 255, 0.22)'
    },
    antd: {
      token: {
        colorPrimary: '#5C8DF6',
        colorInfo: '#5AA7FF',
        colorSuccess: '#3DBE6C',
        colorWarning: '#E5A23C',
        colorError: '#F16580',
        colorBgLayout: '#0F111A',
        colorBgContainer: '#161927',
        colorBgElevated: '#1E2234',
        colorBorder: '#333950',
        colorBorderSecondary: '#262B3E',
        colorText: '#E9EBF5',
        colorTextSecondary: '#A9AEC4',
        colorTextTertiary: '#71778E',
        borderRadius: 10,
        fontFamily: PALETTE.fontFamily
      }
    }
  }
}
