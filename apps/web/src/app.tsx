import { XProvider } from '@ant-design/x';
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import './global.css';

/**
 * Umi 运行时根容器。
 *
 * 统一挂载中文语言包与主题 token，XProvider 负责 Ant Design X 的方向与文案上下文。
 * 认证、租户上下文与请求拦截在 M02 通过独立模块接入，此处不引入任何业务状态。
 *
 * @param container Umi 渲染出的路由树
 * @returns 包裹全局 Provider 后的 React 节点
 */
export function rootContainer(container: ReactNode) {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          // antd 默认主色 #1677ff 在白色背景上的对比度约 4.1:1，不满足 WCAG AA 的 4.5:1；
          // 使用同色系 blue-7 #0958d9（约 6.4:1），同时把次要文本从 #8c8c8c 提升到 #595959。
          colorPrimary: '#0958d9',
          colorLink: '#0958d9',
          colorTextSecondary: '#595959',
          colorTextTertiary: '#595959',
          borderRadius: 8,
        },
      }}
    >
      <XProvider>
        <AntdApp>{container}</AntdApp>
      </XProvider>
    </ConfigProvider>
  );
}
