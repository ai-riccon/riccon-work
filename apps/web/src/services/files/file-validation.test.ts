import { describe, expect, it } from 'vitest';
import {
  MAX_FILE_BYTES,
  MAX_FILES_PER_CONVERSATION,
  formatBytes,
  isParsePending,
  validateFile,
  validateFiles,
} from './file-validation';

/** 构造文件结构。 */
function file(name: string, size = 1024, type = ''): { name: string; size: number; type: string } {
  return { name, size, type };
}

describe('文件校验', () => {
  it('接受五种支持格式并识别 MIME', () => {
    expect(validateFile(file('a.pdf', 1, 'application/pdf'))).toEqual({
      ok: true,
      format: 'PDF',
    });
    expect(validateFile(file('a.docx')).ok).toBe(true);
    expect(validateFile(file('a.xlsx')).ok).toBe(true);
    expect(validateFile(file('a.png', 1, 'image/png')).ok).toBe(true);
    expect(validateFile(file('a.jpeg', 1, 'image/jpeg')).ok).toBe(true);
  });

  it('拒绝不支持的扩展名与缺少扩展名', () => {
    expect(validateFile(file('a.txt')).ok).toBe(false);
    expect(validateFile(file('README')).ok).toBe(false);
    expect(validateFile(file('.pdf')).ok).toBe(false);
  });

  it('拒绝扩展名与 MIME 不一致的文件', () => {
    const result = validateFile(file('a.pdf', 1024, 'text/plain'));
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.reason).toContain('不一致');
    }
  });

  it('允许空 MIME 与 octet-stream，兼容桌面环境', () => {
    expect(validateFile(file('a.pdf', 1, 'application/octet-stream')).ok).toBe(true);
    expect(validateFile(file('a.pdf', 1, '')).ok).toBe(true);
  });

  it('拒绝空文件与超过 200 MB 的文件', () => {
    expect(validateFile(file('a.pdf', 0)).ok).toBe(false);
    expect(validateFile(file('a.pdf', MAX_FILE_BYTES + 1)).ok).toBe(false);
    expect(validateFile(file('a.pdf', MAX_FILE_BYTES)).ok).toBe(true);
  });

  it('批量校验应用单会话 5 个文件上限', () => {
    const files = Array.from({ length: 6 }, (_, index) => file(`f-${index}.pdf`));
    const result = validateFiles(files, 0);

    expect(result.accepted).toHaveLength(MAX_FILES_PER_CONVERSATION);
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0]).toContain('最多上传 5 个');
  });

  it('已有文件数会占用剩余额度，合法文件仍继续处理', () => {
    const result = validateFiles([file('a.pdf'), file('b.pdf')], 4);

    expect(result.accepted).toHaveLength(1);
    expect(result.errors.some((item) => item.includes('最多上传'))).toBe(true);
  });

  it('格式化体积并判断解析中状态', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(MAX_FILE_BYTES)).toBe('200 MB');
    expect(isParsePending('PENDING')).toBe(true);
    expect(isParsePending('RUNNING')).toBe(true);
    expect(isParsePending('SUCCEEDED')).toBe(false);
    expect(isParsePending(undefined)).toBe(false);
  });
});
