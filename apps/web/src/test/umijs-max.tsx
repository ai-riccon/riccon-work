import type { AnchorHTMLAttributes, ReactNode } from 'react';

/**
 * `@umijs/max` 的 Vitest 替身。
 *
 * 页面只使用 Link、Outlet、useLocation 三个运行时能力。测试中渲染真实 anchor，
 * 既能在 jsdom 中验证跳转地址，又避免启动 Umi 的 history 与路由上下文。
 */

/** Link 替身：把 Umi 的 `to` 转换为浏览器可断言的 href。 */
export function Link({
  to,
  children,
  ...rest
}: { to: string; children?: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) {
  return (
    <a href={to} {...rest}>
      {children}
    </a>
  );
}

/** Outlet 替身：用稳定的 testid 证明布局确实渲染了子路由出口。 */
export function Outlet() {
  return <div data-testid="umi-outlet" />;
}

/** useNavigate 替身：页面测试通过 props 注入导航断言；这里只保证 hook 可调用。 */
export function useNavigate() {
  return (to: string, options?: { replace?: boolean }) => {
    void to;
    void options;
  };
}

/** useLocation 替身：固定为管理端总览，用于断言菜单激活态。 */
export function useLocation() {
  return { pathname: '/admin/overview' };
}
