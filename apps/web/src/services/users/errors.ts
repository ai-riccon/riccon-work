import { ApiError } from '@/services/api/errors';

/**
 * 用户管理错误文案。
 *
 * 后端 `ProblemDetail.detail` 是英文内部说明；本模块按状态与稳定错误码映射为可读中文，
 * 不回显原始 detail，避免泄露内部实现或平台租户判定细节。
 */

/**
 * 列表加载失败文案。
 *
 * @param error 任意异常
 * @returns 中文提示
 */
export function describeUserListError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '账号列表加载失败，请稍后重试。';
  }
  switch (error.status) {
    case 0:
      return '无法连接服务器，请检查网络后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '只有具备账号管理权限的管理员可以查看账号列表。';
    case 404:
      return '目标租户不存在或已被删除。';
    case 429:
      return '请求过于频繁，请稍后重试。';
    default:
      return error.status >= 500
        ? '账号列表暂时不可用，请稍后重试。'
        : '账号列表加载失败，请稍后重试。';
  }
}

/**
 * 绑定失败文案。
 *
 * @param error 任意异常
 * @returns 中文提示
 */
export function describeBindError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '绑定企业账号失败，请稍后重试。';
  }
  if (error.code === 'RICCON-OIDC-409' || error.status === 409) {
    return '该企业身份已绑定其他账号，或该账号已绑定其他企业身份。请刷新列表后重试。';
  }
  if (error.code === 'RICCON-OIDC-400' || error.status === 400) {
    return 'issuer 与租户配置不一致，或 issuer/subject 格式不合法。请确认 issuer 与管理员配置完全一致。';
  }
  switch (error.status) {
    case 0:
      return '无法连接服务器，请检查网络后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '没有管理账号的权限。';
    case 404:
      return '账号不存在，或不属于当前租户。';
    default:
      return error.status >= 500
        ? '绑定服务暂时不可用，请稍后重试。'
        : '绑定企业账号失败，请稍后重试。';
  }
}

/**
 * 解绑失败文案。
 *
 * @param error 任意异常
 * @returns 中文提示
 */
export function describeUnbindError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '解除绑定失败，请稍后重试。';
  }
  if (error.code === 'RICCON-OIDC-409' || error.status === 409) {
    return '账号当前未绑定企业身份，或状态已被其他管理员修改。请刷新列表后重试。';
  }
  if (error.code === 'RICCON-OIDC-400' || error.status === 400) {
    return '新密码长度需为 12–200 个字符，且不能包含首尾空白。';
  }
  switch (error.status) {
    case 0:
      return '无法连接服务器，请检查网络后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '没有管理账号的权限。';
    case 404:
      return '账号不存在，或不属于当前租户。';
    default:
      return error.status >= 500
        ? '解绑服务暂时不可用，请稍后重试。'
        : '解除绑定失败，请稍后重试。';
  }
}
