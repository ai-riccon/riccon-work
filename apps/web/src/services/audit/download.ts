/**
 * 浏览器端保存导出文件。
 *
 * 不使用 `window.open`：下载端点要求会话 Cookie，且过期/状态冲突需要留在页面内
 * 展示文案。这里用 Blob URL + 隐藏 anchor 触发下载，URL 在点击后立即释放。
 *
 * @param blob 服务端返回的 CSV
 * @param filename 服务端 Content-Disposition 中的文件名
 * @returns 是否成功触发下载；环境不支持 Blob URL 时返回 false
 */
export function saveExportFile(blob: Blob, filename: string): boolean {
  if (
    typeof document === 'undefined' ||
    typeof URL === 'undefined' ||
    typeof URL.createObjectURL !== 'function'
  ) {
    return false;
  }

  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
  return true;
}
