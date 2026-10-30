// 引入主题类型，仅用于检查冷蓝配置的字段是否正确。
import type { ThemeConfig } from 'antd';

// 主题令牌是单一颜色来源；CSS 使用 main.tsx 写入的同一组变量。
export const tokens = {
  canvas: '#F5F8FC', surface: '#FFFFFF', ink: '#203047', muted: '#5D6E82',
  brand: '#1958A6', action: '#CFE4FF', hover: '#B8D7FF', soft: '#EDF5FF',
  line: '#DFE7F0', focus: '#3877C0',
} as const;
// Ant Design 的主色用于选中与焦点；Button 单独使用浅底，避免深蓝实心按钮。
export const theme: ThemeConfig = {
  token: {
    colorPrimary: tokens.brand, colorInfo: tokens.brand, colorText: tokens.ink,
    colorTextSecondary: tokens.muted, colorBgLayout: tokens.canvas, colorBgContainer: tokens.surface,
    colorBorder: '#D7E2EE', colorBorderSecondary: tokens.line, borderRadius: 10,
    controlHeight: 40, fontSize: 14,
    fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  },
  components: {
    Button: { primaryColor: tokens.brand, primaryShadow: 'none', defaultShadow: 'none', fontWeight: 550 },
    Table: { headerBg: '#F9FAFD', headerColor: tokens.muted, rowHoverBg: '#F3F8FF', cellFontSize: 13 },
    Modal: { borderRadiusLG: 12 },
    Drawer: { footerPaddingBlock: 20 },
  },
};
