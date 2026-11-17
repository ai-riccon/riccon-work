import type { FileFormat } from '@/services/api/types';

/** 单文件上限：200 MB。 */
export const MAX_FILE_BYTES = 200 * 1024 * 1024;

/** 单会话文件上限。 */
export const MAX_FILES_PER_CONVERSATION = 5;

/** 允许的扩展名与格式映射。 */
const EXTENSION_FORMATS: Record<string, FileFormat> = {
  '.pdf': 'PDF',
  '.docx': 'DOCX',
  '.xlsx': 'XLSX',
  '.png': 'PNG',
  '.jpg': 'JPEG',
  '.jpeg': 'JPEG',
};

/** 每种扩展名允许的浏览器 MIME；空 MIME 或 octet-stream 时只按扩展名判断。 */
const EXTENSION_MIME_TYPES: Record<string, string[]> = {
  '.pdf': ['application/pdf'],
  '.docx': ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  '.xlsx': ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
  '.png': ['image/png'],
  '.jpg': ['image/jpeg', 'image/jpg'],
  '.jpeg': ['image/jpeg', 'image/jpg'],
};

/** 文件选择框的 accept 属性。 */
export const FILE_ACCEPT = '.pdf,.docx,.xlsx,.png,.jpg,.jpeg';

/** 校验所需的最小文件结构，便于用普通对象做单元测试。 */
export interface FileLike {
  name: string;
  size: number;
  type?: string;
}

/** 单文件校验结果。 */
export type FileValidation = { ok: true; format: FileFormat } | { ok: false; reason: string };

/**
 * 把字节数格式化为易读文本。
 *
 * @param bytes 字节数
 * @returns 例如 `1.5 KB`、`200 MB`
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const kb = bytes / 1024;
  if (kb < 1024) {
    return `${trimTrailingZero(kb)} KB`;
  }
  return `${trimTrailingZero(kb / 1024)} MB`;
}

/**
 * 校验单个文件的扩展名、MIME 与大小。
 *
 * 扩展名是主判据；浏览器给出明确且不匹配的 MIME 时拒绝，给出空值或
 * `application/octet-stream` 时放行，避免部分桌面环境误判。
 *
 * @param file 文件结构
 * @returns 通过时返回格式，失败时返回中文原因
 */
export function validateFile(file: FileLike): FileValidation {
  const name = file.name.trim();
  const dot = name.lastIndexOf('.');
  if (dot <= 0) {
    return { ok: false, reason: `“${name}”缺少扩展名，无法识别格式。` };
  }

  const extension = name.slice(dot).toLowerCase();
  const format = EXTENSION_FORMATS[extension];
  if (!format) {
    return {
      ok: false,
      reason: `“${name}”格式不受支持，仅支持 PDF、DOCX、XLSX、PNG、JPEG。`,
    };
  }

  const declaredType = (file.type ?? '').toLowerCase();
  if (
    declaredType &&
    declaredType !== 'application/octet-stream' &&
    !EXTENSION_MIME_TYPES[extension].includes(declaredType)
  ) {
    return {
      ok: false,
      reason: `“${name}”的扩展名与文件类型不一致，请重新选择原始文件。`,
    };
  }

  if (file.size <= 0) {
    return { ok: false, reason: `“${name}”是空文件，无法解析。` };
  }
  if (file.size > MAX_FILE_BYTES) {
    return {
      ok: false,
      reason: `“${name}”大小为 ${formatBytes(file.size)}，超过 200 MB 上限。`,
    };
  }

  return { ok: true, format };
}

/** 批量校验结果。 */
export interface BatchValidation<T extends FileLike> {
  accepted: T[];
  errors: string[];
}

/**
 * 批量校验文件并应用会话数量上限。
 *
 * 超过上限的文件不会上传，但已经合法的文件仍会继续处理，避免一次选择多个文件时全部失败。
 *
 * @param files 待上传文件
 * @param existingCount 会话中已有文件数
 * @returns 可上传文件与错误列表
 */
export function validateFiles<T extends FileLike>(
  files: T[],
  existingCount: number,
): BatchValidation<T> {
  const accepted: T[] = [];
  const errors: string[] = [];
  let remaining = Math.max(0, MAX_FILES_PER_CONVERSATION - existingCount);

  for (const file of files) {
    const result = validateFile(file);
    if (!result.ok) {
      errors.push(result.reason);
      continue;
    }
    if (remaining <= 0) {
      errors.push(`每个会话最多上传 ${MAX_FILES_PER_CONVERSATION} 个文件，请删除文件后再试。`);
      continue;
    }
    accepted.push(file);
    remaining -= 1;
  }

  return { accepted, errors };
}

/**
 * 判断解析状态是否仍需轮询。
 *
 * @param status 解析状态
 * @returns 排队或处理中返回 true
 */
export function isParsePending(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

/**
 * 去除小数末尾的 0，保持体积文案简洁。
 *
 * @param value 数值
 * @returns 字符串
 */
function trimTrailingZero(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
}
