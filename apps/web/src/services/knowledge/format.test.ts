import { describe, expect, it } from 'vitest';
import type { Department, SessionInfo } from '@/services/api/types';
import { ApiError } from '@/services/api/errors';
import {
  canDeleteKnowledge,
  canEditKnowledgeAcl,
  canReindexKnowledge,
  hasKnowledgePermission,
} from './access';
import {
  describeKnowledgeError,
  formatKnowledgeSize,
  knowledgeAclNameSummary,
  knowledgeOcrConfidenceText,
  knowledgeParseErrorText,
  knowledgeParseState,
  knowledgeParseStateDescription,
  knowledgeParseStateLabel,
  knowledgeRetentionText,
  knowledgeSafeErrorMessage,
  knowledgeStatusColor,
  knowledgeStatusLabel,
  knowledgeVisibilityLabel,
  knowledgeVisibilitySummary,
  knowledgeWarningTexts,
  parseEngineLabel,
  retentionPolicyLabel,
} from './format';
import type { KnowledgeDocument, KnowledgeDocumentAcl } from './types';

/** 构造文档。 */
function makeDocument(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id: 'doc-1',
    title: '销售数据.xlsx',
    sourceType: 'UPLOAD',
    fileId: 'file-1',
    status: 'INDEXED',
    checksum: 'a'.repeat(64),
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: 2048,
    createdAt: '2026-11-23T10:00:00+08:00',
    visibility: 'TENANT',
    departmentId: null,
    aclUserCount: 0,
    retentionDays: 180,
    retentionPolicy: 'DEPLOYMENT_DEFAULT',
    retentionExpiresAt: '2027-05-22T10:00:00+08:00',
    ...overrides,
  };
}

/** 构造部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-manager',
    managerDisplayName: '李强',
    memberCount: 3,
    createdAt: '2026-11-18T08:00:00+08:00',
    updatedAt: '2026-11-18T08:00:00+08:00',
    ...overrides,
  };
}

/** 构造 ACL。 */
function makeAcl(overrides: Partial<KnowledgeDocumentAcl> = {}): KnowledgeDocumentAcl {
  return {
    visibility: 'USERS',
    departmentId: null,
    creatorUserId: 'user-creator',
    users: [
      { userId: 'user-creator', displayName: '李强', creator: true },
      { userId: 'user-2', displayName: '王敏' },
    ],
    ...overrides,
  };
}

/** 构造租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  authenticated: true,
  user: {
    id: 'user-creator',
    username: 'admin',
    displayName: '李强',
    email: null,
    status: 'ACTIVE',
    roles: ['TENANT_ADMIN'],
    lastLoginAt: null,
    createdAt: '2026-11-18T08:00:00+08:00',
  },
  tenant: {
    id: 'tenant-a',
    name: '示例企业',
    status: 'ACTIVE',
    retentionDays: 180,
    createdAt: '2026-11-18T08:00:00+08:00',
  },
  roles: ['TENANT_ADMIN'],
  permissions: ['knowledge.read', 'knowledge.manage', 'knowledge.acl.manage'],
  csrfToken: 'csrf',
  expiresAt: '2026-11-23T12:00:00+08:00',
};

describe('知识库展示文案', () => {
  it('状态与可见范围使用固定中文名与颜色', () => {
    expect(knowledgeStatusLabel('PENDING')).toBe('排队中');
    expect(knowledgeStatusLabel('INDEXING')).toBe('解析中');
    expect(knowledgeStatusLabel('INDEXED')).toBe('已索引');
    expect(knowledgeStatusLabel('FAILED')).toBe('解析失败');
    expect(knowledgeStatusLabel('DELETED')).toBe('已删除');
    expect(knowledgeStatusColor('INDEXED')).toBe('success');
    expect(knowledgeStatusColor('FAILED')).toBe('error');

    expect(knowledgeVisibilityLabel('TENANT')).toBe('租户全员');
    expect(knowledgeVisibilityLabel('DEPT')).toBe('指定部门');
    expect(knowledgeVisibilityLabel('USERS')).toBe('指定用户');
  });

  it('可见范围摘要覆盖四类：租户、部门、指定用户与创建人隐式可见', () => {
    expect(knowledgeVisibilitySummary(makeDocument())).toBe('本租户全员可见（创建人始终可见）');
    expect(
      knowledgeVisibilitySummary(makeDocument({ visibility: 'DEPT', departmentId: 'dept-sales' }), [
        makeDepartment(),
      ]),
    ).toBe('指定部门：销售部');
    expect(
      knowledgeVisibilitySummary(
        makeDocument({ visibility: 'DEPT', departmentId: 'dept-missing' }),
        [],
      ),
    ).toBe('指定部门（部门信息加载中）');
    expect(knowledgeVisibilitySummary(makeDocument({ visibility: 'USERS', aclUserCount: 3 }))).toBe(
      '指定用户：3 人（创建人始终可见）',
    );
  });

  it('解析引擎、保留策略与保留到期文案完整', () => {
    expect(parseEngineLabel('POI')).toContain('Excel');
    expect(parseEngineLabel('TESSERACT')).toContain('OCR');
    expect(parseEngineLabel(null)).toBe('未记录');
    expect(parseEngineLabel(undefined)).toBe('未记录');
    expect(retentionPolicyLabel('TENANT_POLICY')).toBe('租户策略');
    expect(retentionPolicyLabel(undefined)).toBe('未记录');
    expect(knowledgeRetentionText(makeDocument())).toBe(
      '到期 2027-05-22 10:00:00 +08:00（保留 180 天，部署默认）',
    );
    expect(knowledgeRetentionText(makeDocument({ retentionExpiresAt: null }))).toContain(
      '到期时间未记录',
    );
  });

  it('文件大小格式化边界', () => {
    expect(formatKnowledgeSize(0)).toBe('0 B');
    expect(formatKnowledgeSize(1024)).toBe('1.0 KB');
    expect(formatKnowledgeSize(2048)).toBe('2.0 KB');
    expect(formatKnowledgeSize(10 * 1024 * 1024)).toBe('10 MB');
    expect(formatKnowledgeSize(-1)).toBe('—');
  });
});

describe('解析状态四态与关闭开关', () => {
  it('加载态：PENDING/INDEXING', () => {
    expect(knowledgeParseState(makeDocument({ status: 'PENDING' }))).toBe('loading');
    expect(knowledgeParseState(makeDocument({ status: 'INDEXING' }))).toBe('loading');
    expect(knowledgeParseStateLabel('loading')).toBe('解析中');
  });

  it('成功态：无警告', () => {
    expect(knowledgeParseState(makeDocument())).toBe('success');
    expect(knowledgeParseStateLabel('success')).toBe('识别成功');
    expect(knowledgeParseStateDescription('success')).toContain('可被检索');
  });

  it('低置信：LOW_CONFIDENCE、低置信页或平均置信度 < 0.6', () => {
    expect(knowledgeParseState(makeDocument({ warnings: ['LOW_CONFIDENCE'] }))).toBe(
      'low-confidence',
    );
    expect(knowledgeParseState(makeDocument({ warnings: ['LOW_CONFIDENCE_PAGES:[1,3]'] }))).toBe(
      'low-confidence',
    );
    expect(
      knowledgeParseState(
        makeDocument({
          parseSummary: {
            ocr: { averageConfidence: 0.59, lowConfidencePages: [], failedPages: [] },
          },
        }),
      ),
    ).toBe('low-confidence');
    expect(
      knowledgeParseState(
        makeDocument({
          parseSummary: {
            ocr: { averageConfidence: 0.6, lowConfidencePages: [], failedPages: [] },
          },
        }),
      ),
    ).toBe('success');
    expect(knowledgeParseStateLabel('low-confidence')).toContain('置信度低');
  });

  it('部分失败：FAILED_PAGES 或 parseSummary.ocr.failedPages', () => {
    expect(knowledgeParseState(makeDocument({ warnings: ['FAILED_PAGES:[2,5]'] }))).toBe('partial');
    expect(
      knowledgeParseState(
        makeDocument({
          parseSummary: {
            ocr: { averageConfidence: 0.9, lowConfidencePages: [], failedPages: [2] },
          },
        }),
      ),
    ).toBe('partial');
    expect(knowledgeParseStateLabel('partial')).toBe('部分成功');
    expect(knowledgeParseStateDescription('partial')).toContain('失败页不会产生引用');
  });

  it('失败与 OCR 关闭分别映射', () => {
    expect(
      knowledgeParseState(makeDocument({ status: 'FAILED', errorCode: 'KB_PARSE_CORRUPT' })),
    ).toBe('failed');
    expect(
      knowledgeParseState(makeDocument({ status: 'FAILED', errorCode: 'KB_OCR_DISABLED' })),
    ).toBe('disabled');
    expect(
      knowledgeParseState(makeDocument({ status: 'FAILED', errorCode: 'KB_OCR_LANG_UNAVAILABLE' })),
    ).toBe('disabled');
    expect(
      knowledgeParseState(makeDocument({ status: 'FAILED', errorCode: 'KB_OCR_UNAVAILABLE' })),
    ).toBe('disabled');
    expect(knowledgeParseStateLabel('failed')).toBe('识别失败');
    expect(knowledgeParseStateLabel('disabled')).toBe('识别未启用');
    expect(knowledgeParseStateDescription('disabled')).toContain('原图与文件名仍保留');
  });

  it('警告转换为中文且未知码保留原始码', () => {
    expect(knowledgeWarningTexts(undefined)).toEqual([]);
    const texts = knowledgeWarningTexts([
      'SHEETS_TRUNCATED',
      'ROWS_TRUNCATED:销售数据',
      'HIDDEN_SHEETS_SKIPPED:2',
      'LOW_CONFIDENCE',
      'OCR_PAGE_LIMIT:3',
      'PAGES_WITHOUT_TEXT:2',
      'FAILED_PAGES:[2,5]',
      'LOW_CONFIDENCE_PAGES:[1]',
      'UNKNOWN_WARNING',
    ]);
    expect(texts[0]).toContain('工作表数量超过上限');
    expect(texts[1]).toContain('销售数据');
    expect(texts[2]).toBe('已跳过 2 个隐藏工作表。');
    expect(texts[3]).toContain('核对原文');
    expect(texts[4]).toContain('3 页');
    expect(texts[5]).toContain('2 页');
    expect(texts[6]).toBe('第 2、5 页识别失败。');
    expect(texts[7]).toContain('第 1 页置信度低');
    expect(texts[8]).toBe('解析警告：UNKNOWN_WARNING');
  });

  it('OCR 平均置信度文案', () => {
    expect(
      knowledgeOcrConfidenceText({
        ocr: { averageConfidence: 0.82, lowConfidencePages: [], failedPages: [] },
      }),
    ).toBe('平均置信度 82%');
    expect(knowledgeOcrConfidenceText(null)).toBeNull();
  });
});

describe('失败原因与安全文案', () => {
  it('稳定错误码映射中文原因', () => {
    expect(knowledgeParseErrorText('KB_PARSE_ENCRYPTED')).toContain('加密');
    expect(knowledgeParseErrorText('KB_PARSE_CORRUPT')).toContain('损坏');
    expect(knowledgeParseErrorText('KB_PARSE_RESOURCE_LIMIT')).toContain('资源上限');
    expect(knowledgeParseErrorText('KB_PARSE_TIMEOUT')).toContain('超时');
    expect(knowledgeParseErrorText('KB_NO_TEXT')).toContain('没有可索引');
    expect(knowledgeParseErrorText('KB_OCR_DISABLED')).toContain('OCR 未启用');
    expect(knowledgeParseErrorText('KB_OCR_LANG_UNAVAILABLE')).toContain('语言包');
    expect(knowledgeParseErrorText('KB_OCR_TIMEOUT')).toContain('其余页');
    expect(knowledgeParseErrorText('KB_OCR_FAILED')).toContain('识别引擎');
    expect(knowledgeParseErrorText('KB_OCR_UNAVAILABLE')).toContain('扫描件');
    expect(knowledgeParseErrorText('KB_UNKNOWN')).toContain('KB_UNKNOWN');
    expect(knowledgeParseErrorText(null)).toContain('解析失败');
  });

  it('服务端说明过滤堆栈与超长文本', () => {
    expect(knowledgeSafeErrorMessage('文件损坏')).toBe('文件损坏');
    expect(knowledgeSafeErrorMessage('  ')).toBeNull();
    expect(knowledgeSafeErrorMessage('at java.lang.Some.method(Some.java:1)')).toBeNull();
    expect(knowledgeSafeErrorMessage('x'.repeat(201))).toBeNull();
    expect(knowledgeSafeErrorMessage('SQLException: select')).toBeNull();
  });

  it('知识库错误映射覆盖 401/403/404/409/502/503/400', () => {
    expect(describeKnowledgeError(new ApiError('x', { status: 401, code: 'A' }))).toContain(
      '重新登录',
    );
    expect(describeKnowledgeError(new ApiError('x', { status: 403, code: 'A' }))).toContain(
      '可见范围',
    );
    expect(describeKnowledgeError(new ApiError('x', { status: 404, code: 'RICCON-KB-404' }))).toBe(
      '该文档没有可下载的源文件。',
    );
    expect(
      describeKnowledgeError(new ApiError('x', { status: 404, code: 'RICCON-TENANT-404' })),
    ).toContain('不存在');
    expect(describeKnowledgeError(new ApiError('x', { status: 409, code: 'A' }))).toContain('刷新');
    expect(describeKnowledgeError(new ApiError('x', { status: 502, code: 'A' }))).toContain(
      '源文件读取失败',
    );
    expect(describeKnowledgeError(new ApiError('x', { status: 503, code: 'A' }))).toContain(
      '索引服务',
    );
    expect(describeKnowledgeError(new ApiError('部门必须启用', { status: 400, code: 'A' }))).toBe(
      '部门必须启用',
    );
    expect(describeKnowledgeError(new Error('boom'))).toContain('未完成');
  });
});

describe('知识库权限', () => {
  it('权限判断未加载时按无权限处理', () => {
    expect(hasKnowledgePermission(['knowledge.read'], 'knowledge.read')).toBe(true);
    expect(hasKnowledgePermission(undefined, 'knowledge.read')).toBe(false);
  });

  it('ACL 编辑与删除只对创建人或 tenant admin 开放', () => {
    expect(canEditKnowledgeAcl(ADMIN_SESSION, makeAcl())).toBe(true);
    expect(canEditKnowledgeAcl(null, makeAcl())).toBe(false);
    expect(canEditKnowledgeAcl(ADMIN_SESSION, null)).toBe(true);
    const member: SessionInfo = {
      ...ADMIN_SESSION,
      user: { ...ADMIN_SESSION.user, id: 'user-member' },
      roles: ['MEMBER'],
      permissions: ['knowledge.read'],
    };
    expect(canEditKnowledgeAcl(member, makeAcl())).toBe(false);
    expect(canDeleteKnowledge(member, makeAcl())).toBe(false);
    expect(canDeleteKnowledge(ADMIN_SESSION, makeAcl())).toBe(true);
  });

  it('重索引要求 knowledge.manage，创建人例外不替代 RBAC 门', () => {
    expect(canReindexKnowledge(ADMIN_SESSION)).toBe(true);
    expect(canReindexKnowledge(null)).toBe(false);
    const creatorWithoutManage: SessionInfo = {
      ...ADMIN_SESSION,
      permissions: ['knowledge.read', 'knowledge.acl.manage'],
    };
    expect(canReindexKnowledge(creatorWithoutManage)).toBe(false);
  });
});

describe('ACL 名单摘要', () => {
  it('最多展示 5 个姓名并标注总数，空名单只显示创建人', () => {
    expect(knowledgeAclNameSummary([])).toBe('仅创建人可见');
    expect(
      knowledgeAclNameSummary([{ displayName: '李强', creator: true }, { displayName: '王敏' }]),
    ).toBe('李强（创建人）、王敏');
    const many = Array.from({ length: 7 }, (_value, index) => ({
      displayName: `用户${index}`,
    }));
    expect(knowledgeAclNameSummary(many)).toBe('用户0、用户1、用户2、用户3、用户4 等 7 人');
  });
});
