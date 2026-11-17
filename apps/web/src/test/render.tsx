import { XProvider } from '@ant-design/x';
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactElement } from 'react';

/**
 * 组件测试统一 Provider。
 *
 * 与 `app.tsx` 的 rootContainer 对齐：中文语言包 + antd App 上下文 + XProvider，
 * 避免组件测试因 locale 或 message 容器缺失产生与生产不同的行为。
 *
 * @param ui 被测组件
 * @returns 包裹 Provider 的节点
 */
export function withProviders(ui: ReactElement) {
  return (
    <ConfigProvider locale={zhCN}>
      <XProvider>
        <AntdApp>{ui}</AntdApp>
      </XProvider>
    </ConfigProvider>
  );
}
