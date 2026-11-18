/**
 * Riccon E2E stub API。
 *
 * 定位：端到端测试专用的独立 HTTP 服务，**不是**应用内 mock。它不进入 Web 构建产物，
 * 只由 Playwright 的 webServer 启动，通过开发代理 `/api` 被浏览器访问，从而同时验证：
 * - 真实 `ApiClient` 的 Cookie/CSRF/ProblemDetail/分页/幂等键路径；
 * - 真实 `fetch` + `ReadableStream` 的 SSE 增量、停止与断线；
 * - multipart 上传、解析轮询、审批状态机与产物预签名下载。
 *
 * 边界：只实现 M02 J1/J2 使用的 OpenAPI 子集；数据全部为合成内容，存活于进程内存。
 * 测试之间通过 `POST /__stub__/reset` 重置；控制端点不属于产品契约。
 *
 * 运行：`node e2e/stub/server.mjs`（Node 内置模块，无第三方依赖）。
 */
import { randomUUID } from 'node:crypto';
import http from 'node:http';

/** 监听地址与端口；Playwright 通过 STUB_PORT 指定，默认与 Web 端口区分。 */
const PORT = Number(process.env.STUB_PORT ?? 8124);
const HOST = process.env.STUB_HOST ?? '127.0.0.1';

/** 会话 Cookie 名；与真实 API 的 HttpOnly 会话语义一致，stub 仅用于 E2E。 */
const SESSION_COOKIE = 'RICCON_STUB_SESSION';

/** E2E 演示凭据；与 Playwright 用例共享，不含真实账号。 */
const DEMO_CREDENTIALS = {
  tenantCode: 'acme',
  username: 'demo',
  password: 'demo1234',
};

/** 文件约束与格式表，与 M02 契约和前端校验一致。 */
const MAX_FILE_BYTES = 200 * 1024 * 1024;
const MAX_FILES_PER_CONVERSATION = 5;
const EXTENSION_FORMATS = {
  '.pdf': 'PDF',
  '.docx': 'DOCX',
  '.xlsx': 'XLSX',
  '.png': 'PNG',
  '.jpg': 'JPEG',
  '.jpeg': 'JPEG',
};

/** 解析推进时间：400ms 进入 RUNNING，900ms 进入终态。 */
const PARSE_RUNNING_AFTER_MS = 400;
const PARSE_FINISH_AFTER_MS = 900;

/** SSE 增量间隔；E2E 有足够时间在流结束前点击停止。 */
const DELTA_INTERVAL_MS = 350;

/** J2 阶段推进时间；与前端 900ms 轮询配合，1–2 秒内可见阶段切换。 */
const RESEARCH_RETRIEVAL_MS = 500;
const RESEARCH_TOOL_MS = 600;
const RESEARCH_GENERATION_MS = 800;

/** 合成 ID；只用于 E2E 断言，不对应任何真实对象。 */
const DEMO_WORKSPACE_ID = '11111111-1111-4111-8111-111111111111';
const DEMO_USER_ID = '22222222-2222-4222-8222-222222222222';
const DEMO_TENANT_ID = 'tenant-acme';
const DEMO_MCP_TOOL_ID = '44444444-4444-4444-8444-444444444444';
const DEMO_KNOWLEDGE_ID_ONE = '55555555-5555-4555-8555-555555555555';
const DEMO_KNOWLEDGE_ID_TWO = '66666666-6666-4666-8666-666666666666';
const DEMO_MODEL_ID = '33333333-3333-4333-8333-333333333333';

/** M03 stub 账号与租户；只用于 E2E 合成数据，不对应真实身份。 */
const M03_TENANT_ACME = 'tenant-acme';
const M03_TENANT_BETA = 'tenant-beta';
const M03_TENANT_SUSPENDED = 'tenant-suspended';
const M03_PLATFORM_TENANT = 'platform';
const M03_ADMIN_USER_ID = '77777777-7777-4777-8777-777777777777';
const M03_MANAGER_USER_ID = '88888888-8888-4888-8888-888888888888';
const M03_OUTSIDER_USER_ID = '99999999-9999-4999-8999-999999999999';
const M03_ADMIN_USERNAME = 'admin';
const M03_MANAGER_USERNAME = 'manager';
const M03_OUTSIDER_USERNAME = 'outsider';
const M03_PLATFORM_USERNAME = 'platform-admin';
const M03_DEMO_DISPLAY_NAME = '演示员工';
const M03_MEMBER_USER_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const M03_MEMBER_USERNAME = 'wangmin';
const M03_MEMBER_DISPLAY_NAME = '王敏';
const M03_MANAGER_DISPLAY_NAME = '李强';
const M03_OUTSIDER_DISPLAY_NAME = '赵敏';
const M03_ADMIN_DISPLAY_NAME = '王芳';
const M03_PLATFORM_DISPLAY_NAME = '赵蕾';
const M03_SALES_DEPARTMENT_ID = 'dept-sales';
const M03_MARKETING_DEPARTMENT_ID = 'dept-marketing';
const M03_SALES_WORKSPACE_ID = 'ws-sales-project';
const M03_SOURCE_UNAVAILABLE_FILE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const M03_SOURCE_UNAVAILABLE_FILE_NAME = '销售部市场分析.pdf';

/**
 * E2E 登录账号表；key 为 `tenantCode/username`。
 *
 * 平台管理员只用于 /admin 部门管理页；租户管理员、负责人与普通成员用于空间共享与知识库边界。
 */
const M03_CREDENTIALS = {
  'acme/demo': {
    password: 'demo1234',
    persona: 'member',
    userId: DEMO_USER_ID,
    username: 'demo',
    displayName: M03_DEMO_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/wangmin': {
    password: 'wangmin1234',
    persona: 'member',
    userId: M03_MEMBER_USER_ID,
    username: M03_MEMBER_USERNAME,
    displayName: M03_MEMBER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/manager': {
    password: 'manager1234',
    persona: 'manager',
    userId: M03_MANAGER_USER_ID,
    username: M03_MANAGER_USERNAME,
    displayName: M03_MANAGER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/admin': {
    password: 'admin1234',
    persona: 'tenant-admin',
    userId: M03_ADMIN_USER_ID,
    username: M03_ADMIN_USERNAME,
    displayName: M03_ADMIN_DISPLAY_NAME,
    roles: ['TENANT_ADMIN'],
    permissions: [
      'workspace.read',
      'workspace.write',
      'conversation.read',
      'department.read',
      'knowledge.read',
      'knowledge.manage',
    ],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/outsider': {
    password: 'outsider1234',
    persona: 'outsider',
    userId: M03_OUTSIDER_USER_ID,
    username: M03_OUTSIDER_USERNAME,
    displayName: M03_OUTSIDER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'platform/platform-admin': {
    password: 'platform1234',
    persona: 'platform-admin',
    userId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    username: M03_PLATFORM_USERNAME,
    displayName: M03_PLATFORM_DISPLAY_NAME,
    roles: ['PLATFORM_ADMIN'],
    permissions: ['platform.tenant.manage', 'platform.department.manage'],
    tenantId: M03_PLATFORM_TENANT,
    tenantName: '平台',
  },
};

/** 合成时间基准，保证刷新后详情时间稳定可读。 */
const BASE_TIME = '2026-11-17T08:00:00.000Z';

/** 会话表：sid -> { authenticated, csrfToken }。 */
const sessions = new Map();

/** 活动 SSE 运行：runId -> run。 */
const activeRuns = new Map();

/** 当前 E2E 场景开关；只影响错误路径用例。 */
let scenario = 'none';

/** 自增计数，用于 traceId。 */
let traceCounter = 0;

/** 业务状态；reset 时整体替换。 */
let state = createInitialState();

/**
 * 创建初始业务状态。
 *
 * @returns 空会话、空任务与一条授权 MCP 工具的合成状态
 */
function createInitialState() {
  return {
    workspace: {
      id: DEMO_WORKSPACE_ID,
      kind: 'PERSONAL',
      name: '我的工作空间',
      description: '用于 J1/J2 E2E 的个人工作空间（合成数据）',
      ownerUserId: DEMO_USER_ID,
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    conversations: [],
    messages: {},
    files: [],
    parseOutcomes: {},
    runs: {},
    researchTasks: [],
    mcpTools: [
      {
        id: DEMO_MCP_TOOL_ID,
        serverId: null,
        name: 'market_data_lookup',
        description: '只读市场数据查询（E2E stub 合成数据，不连接真实外部系统）',
        riskLevel: 'READ',
        requiresApproval: true,
        status: 'ACTIVE',
      },
    ],
    researchIdempotency: {},
    // M03：平台租户元数据、部门与团队空间；所有写操作只改内存。
    tenants: [
      {
        id: M03_TENANT_ACME,
        name: '示例企业',
        status: 'ACTIVE',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
      {
        id: M03_TENANT_BETA,
        name: 'Beta 演示企业',
        status: 'ACTIVE',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
      {
        id: M03_TENANT_SUSPENDED,
        name: '暂停租户',
        status: 'SUSPENDED',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
    ],
    users: [
      {
        id: DEMO_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: 'demo',
        displayName: M03_DEMO_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_MEMBER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_MEMBER_USERNAME,
        displayName: M03_MEMBER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_MANAGER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_MANAGER_USERNAME,
        displayName: M03_MANAGER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_OUTSIDER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_OUTSIDER_USERNAME,
        displayName: M03_OUTSIDER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_ADMIN_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_ADMIN_USERNAME,
        displayName: M03_ADMIN_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['TENANT_ADMIN'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
    ],
    departments: [],
    teamWorkspaces: [],
    workspaceMembers: {},
  };
}

/**
 * 生成唯一 ID。
 *
 * @param prefix 兼容旧运行环境的可读前缀
 * @returns UUID 或带前缀的稳定 ID
 */
function nextId(prefix) {
  return typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 生成 traceId。
 *
 * @returns 可读的 trace 标识
 */
function nextTraceId() {
  traceCounter += 1;
  return `stub-trace-${Date.now()}-${traceCounter}`;
}

/**
 * 生成 64 位十六进制伪哈希；E2E 不校验真实内容哈希。
 *
 * @param seed 种子文本
 * @returns 64 位十六进制字符串
 */
function pseudoSha256(seed) {
  let hash = 2166136261;
  for (let index = 0; index < seed.length; index += 1) {
    hash ^= seed.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  const chunk = (hash >>> 0).toString(16).padStart(8, '0');
  return chunk.repeat(8).slice(0, 64);
}

/**
 * 重置业务状态、会话与活动运行；供每个 E2E 用例开始时调用。
 */
function resetAll() {
  for (const run of activeRuns.values()) {
    if (run.timer !== null) {
      clearInterval(run.timer);
    }
  }
  activeRuns.clear();
  sessions.clear();
  scenario = 'none';
  state = createInitialState();
}

/**
 * 构造当前会话响应。
 *
 * @param session 会话记录
 * @returns OpenAPI SessionInfo
 */
/** 员工端完整权限；部门管理入口仍由服务端角色与部门负责人关系决定。 */
const MEMBER_PERMISSIONS = [
  'workspace.read',
  'conversation.read',
  'conversation.write',
  'file.read',
  'file.upload',
  'report.read',
  'report.create',
  'report.approve',
  'artifact.read',
  'mcp.read',
  'department.read',
  'knowledge.read',
];

/**
 * 构造当前会话响应。
 *
 * @param session 会话记录
 * @returns OpenAPI SessionInfo
 */
function sessionInfo(session) {
  const account = session.account ?? M03_CREDENTIALS['acme/demo'];
  const isMemberPersona = ['member', 'manager', 'outsider'].includes(account.persona);
  const tenant = state.tenants.find((item) => item.id === account.tenantId);
  return {
    authenticated: true,
    user: {
      id: account.userId,
      username: account.username,
      displayName: account.displayName,
      email: null,
      status: 'ACTIVE',
      roles: account.roles,
      lastLoginAt: BASE_TIME,
      createdAt: BASE_TIME,
    },
    tenant: {
      id: account.tenantId,
      name: tenant?.name ?? account.tenantName,
      status: tenant?.status ?? 'ACTIVE',
      retentionDays: tenant?.retentionDays ?? 180,
      createdAt: BASE_TIME,
    },
    roles: account.roles,
    permissions: isMemberPersona ? MEMBER_PERMISSIONS : account.permissions,
    csrfToken: session.csrfToken,
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  };
}

/** 分页响应。 */
function page(items) {
  return {
    page: 1,
    size: 100,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 构造 RFC 9457 ProblemDetail。
 *
 * @param status HTTP 状态
 * @param code 稳定错误码
 * @param title 短标题
 * @param detail 可读原因
 * @returns 问题详情对象
 */
function problem(status, code, title, detail) {
  return {
    body: {
      type: 'about:blank',
      title,
      status,
      detail,
      code,
      traceId: nextTraceId(),
    },
    status,
    contentType: 'application/problem+json; charset=utf-8',
  };
}

/** 成功 JSON 响应。 */
function json(data, status = 200) {
  return { body: data, status, contentType: 'application/json; charset=utf-8' };
}

/**
 * 解析 Cookie 头。
 *
 * @param header Cookie 请求头
 * @returns 名称到值的映射
 */
function parseCookies(header) {
  const result = {};
  if (!header) {
    return result;
  }
  for (const part of header.split(';')) {
    const separator = part.indexOf('=');
    if (separator === -1) {
      continue;
    }
    result[part.slice(0, separator).trim()] = decodeURIComponent(part.slice(separator + 1).trim());
  }
  return result;
}

/**
 * 读取请求会话。
 *
 * @param req Node 请求
 * @returns 会话记录或 null
 */
function readSession(req) {
  const sid = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  return sid ? (sessions.get(sid) ?? null) : null;
}

/**
 * 确保请求带 Cookie 会话；匿名请求会建立仅承载 CSRF 的会话。
 *
 * @param req Node 请求
 * @returns 会话记录（可能未登录）
 */
function ensureSession(req) {
  const existing = readSession(req);
  if (existing) {
    return { session: existing, created: false };
  }
  const sid = nextId('session');
  const session = { authenticated: false, csrfToken: `stub-csrf-${nextId('csrf')}` };
  sessions.set(sid, session);
  return { session, created: true, sid };
}

/**
 * 清除会话。
 *
 * @param req Node 请求
 */
function dropSession(req) {
  const sid = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  if (sid) {
    sessions.delete(sid);
  }
}

/**
 * 校验登录态。
 *
 * @param session 会话记录
 * @returns 401 响应或 null
 */
function requireAuth(session) {
  if (!session?.authenticated) {
    return problem(401, 'RICCON-AUTH-401', '未登录', '请先登录后再访问。');
  }
  return null;
}

/**
 * 校验 CSRF 头。
 *
 * @param session 会话记录
 * @param req Node 请求
 * @returns 403 响应或 null
 */
function requireCsrf(session, req) {
  if (!session || req.headers['x-csrf-token'] !== session.csrfToken) {
    return problem(403, 'RICCON-AUTH-403', '安全令牌无效', '请刷新页面后重试。');
  }
  return null;
}

/**
 * 读取 JSON 请求体。
 *
 * @param raw 原始字节
 * @returns 解析后的对象；非法 JSON 返回 null
 */
function readJson(raw) {
  if (!raw || raw.length === 0) {
    return null;
  }
  try {
    return JSON.parse(raw.toString('utf8'));
  } catch {
    return null;
  }
}

/**
 * 判断文件字节是否为允许的格式；只校验扩展名与声明 MIME，内容为合成数据。
 *
 * @param filename 文件名
 * @param mimeType 声明 MIME
 * @returns 格式或 null
 */
function formatForFile(filename, mimeType) {
  const dot = filename.lastIndexOf('.');
  if (dot <= 0) {
    return null;
  }
  const format = EXTENSION_FORMATS[filename.slice(dot).toLowerCase()];
  if (!format) {
    return null;
  }
  const declared = (mimeType ?? '').toLowerCase();
  const allowed = {
    '.pdf': ['application/pdf'],
    '.docx': ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    '.xlsx': ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
    '.png': ['image/png'],
    '.jpg': ['image/jpeg', 'image/jpg'],
    '.jpeg': ['image/jpeg', 'image/jpg'],
  }[filename.slice(dot).toLowerCase()];
  if (declared && declared !== 'application/octet-stream' && !allowed.includes(declared)) {
    return null;
  }
  return format;
}

/**
 * 解析 multipart/form-data 请求体。
 *
 * 只需要 J1 上传的 `file` 与 `conversationId` 两个字段；按 boundary 切分，
 * 不做通用 MIME 解析，避免为 E2E stub 引入第三方依赖。
 *
 * @param body 原始请求体
 * @param contentType Content-Type 头
 * @returns 字段与文件；无法解析时返回 null
 */
function parseMultipart(body, contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType ?? '');
  if (!match || !body) {
    return null;
  }
  const boundary = Buffer.from(`--${match[1] ?? match[2]}`);
  const fields = {};
  const files = {};

  let cursor = body.indexOf(boundary);
  while (cursor !== -1) {
    const next = body.indexOf(boundary, cursor + boundary.length);
    if (next === -1) {
      break;
    }
    let part = body.subarray(cursor + boundary.length, next);
    // 去掉分隔符后的 CRLF 与下一个 boundary 前的 CRLF。
    if (part[0] === 13 && part[1] === 10) {
      part = part.subarray(2);
    }
    if (part.length >= 2 && part[part.length - 2] === 13 && part[part.length - 1] === 10) {
      part = part.subarray(0, part.length - 2);
    }
    // 结束分隔符 `--` 不是有效 part。
    if (part.length > 2 && part[0] === 45 && part[1] === 45) {
      cursor = next;
      continue;
    }
    const headerEnd = part.indexOf('\r\n\r\n');
    if (headerEnd !== -1) {
      const headerText = part.subarray(0, headerEnd).toString('utf8');
      const data = part.subarray(headerEnd + 4);
      const nameMatch = /name="([^"]*)"/i.exec(headerText);
      const filenameMatch = /filename="([^"]*)"/i.exec(headerText);
      const typeMatch = /content-type:\s*([^\r\n]+)/i.exec(headerText);
      if (nameMatch) {
        if (filenameMatch) {
          files[nameMatch[1]] = {
            filename: filenameMatch[1],
            contentType: typeMatch?.[1]?.trim() ?? 'application/octet-stream',
            data,
          };
        } else {
          fields[nameMatch[1]] = data.toString('utf8');
        }
      }
    }
    cursor = next;
  }

  return { fields, files };
}

/**
 * 按真实时间推进文件解析状态。
 */
function advanceParses() {
  const now = Date.now();
  for (const file of state.files) {
    if (!file.parse || (file.parse.status !== 'PENDING' && file.parse.status !== 'RUNNING')) {
      continue;
    }
    const startedAt = file.parse.startedAt ? Date.parse(file.parse.startedAt) : now;
    const elapsed = Math.max(0, now - startedAt);
    if (elapsed < PARSE_RUNNING_AFTER_MS) {
      continue;
    }
    if (elapsed < PARSE_FINISH_AFTER_MS && file.parse.status !== 'RUNNING') {
      file.parse.status = 'RUNNING';
      continue;
    }
    if (elapsed >= PARSE_FINISH_AFTER_MS) {
      const outcome = state.parseOutcomes[file.id];
      if (outcome?.fail) {
        file.parse.status = 'FAILED';
        file.parse.errorCode = 'RICCON-FILE-500';
        file.parse.errorMessage = '文件内容无法解析，请确认文件未加密或未损坏后重试。';
      } else {
        file.parse.status = 'SUCCEEDED';
        file.parse.pageCount = file.format === 'PDF' ? 12 : null;
        file.parse.sheetCount = file.format === 'XLSX' ? 3 : null;
        file.parse.chunkCount = 8;
        file.parse.errorCode = null;
        file.parse.errorMessage = null;
      }
      file.parse.finishedAt = new Date(now).toISOString();
    }
  }
}

/**
 * 根据文件格式生成引用定位。
 *
 * @param file 已解析文件
 * @param ordinal 引用序号
 * @returns OpenAPI Citation
 */
function citationForFile(file, ordinal) {
  const base = {
    ordinal,
    sourceType: 'FILE',
    fileId: file.id,
    knowledgeDocumentId: null,
    chunkId: null,
    title: file.originalName,
    snippet: `演示引用片段：${file.originalName} 中与问题相关的内容。`,
    score: 0.82,
  };
  switch (file.format) {
    case 'XLSX':
      return {
        ...base,
        locatorType: 'SHEET',
        sheetName: '销售数据',
        cellRange: 'B2:D8',
        pageNo: null,
        paragraphNo: null,
      };
    case 'DOCX':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 5,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PNG':
    case 'JPEG':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 1,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PDF':
    default:
      return {
        ...base,
        locatorType: 'PAGE',
        pageNo: 3,
        sheetName: null,
        cellRange: null,
        paragraphNo: null,
      };
  }
}

/**
 * 构造一条跨部门知识库来源引用；源文件对当前账号不可访问，用于验证 404 文案。
 *
 * @returns 引用快照
 */
function unavailableSourceCitation() {
  return {
    ordinal: 1,
    sourceType: 'KNOWLEDGE',
    fileId: M03_SOURCE_UNAVAILABLE_FILE_ID,
    knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
    chunkId: 'chunk-other-department',
    title: M03_SOURCE_UNAVAILABLE_FILE_NAME,
    snippet: '演示引用片段：该文档属于其他部门的共享空间，引用可展示但源文件不可访问。',
    score: 0.76,
    locatorType: 'PAGE',
    pageNo: 3,
    paragraphNo: null,
    sheetName: null,
    cellRange: null,
  };
}

/**
 * 生成合成回答。
 *
 * @param question 用户问题
 * @param citations 引用
 * @returns 回答全文
 */
function buildAnswer(question, citations) {
  if (citations.length === 0) {
    return '当前知识库未检索到相关资料。你可以先上传文件，或用更具体的关键词重新提问。';
  }
  const first = citations[0];
  const locator =
    first.locatorType === 'SHEET'
      ? `工作表 ${first.sheetName ?? ''}`
      : first.locatorType === 'PAGE'
        ? `第 ${first.pageNo ?? 1} 页`
        : `第 ${first.paragraphNo ?? 1} 段`;
  return [
    `根据你上传的文件《${first.title}》，我找到以下信息：`,
    '',
    `1. 文件已完成解析，可用于回答与「${question.slice(0, 40)}」相关的问题。`,
    `2. 引用位置：${locator}。`,
    '3. 回答内容仅用于演示 J1 流式与引用链路，不代表真实检索结果。',
    '',
    '如需进一步分析，可以继续追问，或上传更多文件。',
  ].join('\n');
}

/**
 * 把回答切成小段，模拟流式输出。
 *
 * @param answer 回答全文
 * @returns 文本片段
 */
function splitAnswer(answer) {
  const size = 24;
  const chunks = [];
  for (let index = 0; index < answer.length; index += size) {
    chunks.push(answer.slice(index, index + size));
  }
  return chunks;
}

/**
 * 构造 SSE 事件信封。
 *
 * @param runId 运行 ID
 * @param messageId 助手消息 ID
 * @param eventType 契约事件类型
 * @param data 事件数据
 * @returns ChatEvent
 */
function createEvent(runId, messageId, eventType, data) {
  return {
    eventId: nextId('event'),
    eventType,
    version: 1,
    occurredAt: new Date().toISOString(),
    tenantId: DEMO_TENANT_ID,
    runId,
    messageId,
    traceId: nextTraceId(),
    data,
  };
}

/**
 * 序列化 SSE 帧。
 *
 * @param event 事件
 * @returns `event:` + `data:` + 空行
 */
function encodeEvent(event) {
  return `event: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`;
}

/**
 * 结束运行并持久化助手消息。
 *
 * @param run 活动运行
 * @param status 消息终态
 */
function finalizeRun(run, status) {
  const messages = state.messages[run.conversationId] ?? [];
  const assistant = messages.find((message) => message.id === run.assistantId);
  if (assistant) {
    assistant.content = run.content;
    assistant.status = status;
    assistant.citations = run.citations;
    assistant.runId = run.runId;
  }
  const conversation = state.conversations.find((item) => item.id === run.conversationId);
  if (conversation) {
    conversation.lastMessageAt = new Date().toISOString();
    conversation.updatedAt = conversation.lastMessageAt;
  }

  // 运行详情与消息终态保持一致：完成/失败/停止分别映射 SUCCEEDED/FAILED/CANCELLED。
  const detail = state.runs[run.runId];
  if (detail) {
    const finishedAt = new Date().toISOString();
    const invocation = detail.modelInvocations[0];
    detail.finishedAt = finishedAt;
    detail.status =
      status === 'COMPLETED' ? 'SUCCEEDED' : status === 'FAILED' ? 'FAILED' : 'CANCELLED';
    if (status === 'FAILED') {
      detail.errorCode = 'RICCON-MODEL-500';
      detail.errorMessage = '合成模型调用失败，请重试。';
    }
    if (invocation) {
      invocation.status =
        status === 'COMPLETED' ? 'SUCCEEDED' : status === 'FAILED' ? 'FAILED' : 'CANCELLED';
      // 失败在首字前终止，不能伪造首字延迟；其他终态保留合成的流式指标。
      invocation.firstTokenMs = status === 'FAILED' ? null : DELTA_INTERVAL_MS;
      invocation.durationMs = Math.max(
        1,
        Date.parse(finishedAt) - Date.parse(invocation.startedAt),
      );
      invocation.promptTokens = 128;
      invocation.completionTokens = Math.max(0, Math.ceil(run.content.length / 2));
      invocation.errorCode = status === 'FAILED' ? 'RICCON-MODEL-500' : null;
      invocation.finishedAt = finishedAt;
    }
  }
}

/**
 * 关闭一次运行；幂等，确保定时器、消息终态与活动表一致。
 *
 * @param run 活动运行
 * @param status 消息终态
 */
function finishRun(run, status) {
  if (run.closed) {
    return;
  }
  run.closed = true;
  if (run.timer !== null) {
    clearInterval(run.timer);
    run.timer = null;
  }
  finalizeRun(run, status);
  activeRuns.delete(run.runId);
}

/**
 * 处理“发送消息并流式回答”。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param conversation 会话
 * @param raw 已读取的请求体
 */
function handleSendMessage(req, res, conversation, raw) {
  const body = readJson(raw);
  const content = typeof body?.content === 'string' ? body.content.trim() : '';
  const clientMessageId = typeof body?.clientMessageId === 'string' ? body.clientMessageId : '';
  if (!content || content.length > 20000 || clientMessageId.length < 8) {
    writeResult(res, problem(400, 'RICCON-CHAT-400', '消息不合法', '消息内容为空或超出长度限制。'));
    return;
  }

  const requestedFileIds = Array.isArray(body?.fileIds)
    ? body.fileIds.filter((id) => typeof id === 'string')
    : [];
  if (requestedFileIds.length > MAX_FILES_PER_CONVERSATION) {
    writeResult(res, problem(400, 'RICCON-CHAT-400', '附件过多', '单条消息最多附带 5 个文件。'));
    return;
  }

  advanceParses();
  const files = requestedFileIds
    .map((fileId) => state.files.find((file) => file.id === fileId))
    .filter(Boolean)
    .slice(0, MAX_FILES_PER_CONVERSATION);
  const usableFiles = files.filter((file) => file.parse?.status === 'SUCCEEDED');

  const messages = state.messages[conversation.id] ?? [];
  const nextSeq = messages.reduce((max, message) => Math.max(max, message.seq), 0) + 1;
  const userMessage = {
    id: nextId('message'),
    conversationId: conversation.id,
    seq: nextSeq,
    role: 'USER',
    content,
    status: 'COMPLETED',
    clientMessageId,
    citations: [],
    createdAt: new Date().toISOString(),
  };
  const assistantId = nextId('message');
  const assistantMessage = {
    id: assistantId,
    conversationId: conversation.id,
    seq: nextSeq + 1,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    citations: [],
    createdAt: new Date().toISOString(),
  };
  state.messages[conversation.id] = [...messages, userMessage, assistantMessage];

  if (!conversation.title) {
    conversation.title = content.length > 20 ? `${content.slice(0, 20)}…` : content;
  }
  conversation.updatedAt = new Date().toISOString();
  conversation.lastMessageAt = conversation.updatedAt;

  const citations =
    scenario === 'citation-source-404'
      ? [unavailableSourceCitation()]
      : usableFiles.slice(0, 2).map((file, index) => citationForFile(file, index + 1));
  const wantsNoCitation = /无引用|无资料|没有资料/.test(content);
  const effectiveCitations = wantsNoCitation ? [] : citations;
  const answer = buildAnswer(content, effectiveCitations);
  const answerChunks = splitAnswer(answer);
  const wantsFailure = /失败|出错|error/i.test(content);

  const runId = nextId('run');
  const run = {
    runId,
    conversationId: conversation.id,
    assistantId,
    messageId: assistantId,
    content: '',
    citations: [],
    queue: [
      createEvent(runId, assistantId, 'riccon.chat.started.v1', {
        modelId: DEMO_MODEL_ID,
        modelKey: 'stub-fake-model',
      }),
      ...(effectiveCitations.length > 0
        ? [
            createEvent(runId, assistantId, 'riccon.chat.citation.v1', {
              citations: effectiveCitations,
            }),
          ]
        : []),
      ...answerChunks.map((delta) =>
        createEvent(runId, assistantId, 'riccon.chat.delta.v1', { delta }),
      ),
      wantsFailure
        ? createEvent(runId, assistantId, 'riccon.chat.failed.v1', {
            code: 'RICCON-MODEL-500',
            message: '合成模型调用失败，请重试。',
            retryable: true,
          })
        : createEvent(runId, assistantId, 'riccon.chat.completed.v1', {
            finishReason: 'stop',
            firstTokenMs: DELTA_INTERVAL_MS,
          }),
    ],
    cursor: 0,
    timer: null,
    closed: false,
  };
  activeRuns.set(runId, run);

  // 运行详情快照独立于活动 SSE 运行保存；完成后仍可通过 GET /api/v1/runs/{runId} 读取。
  const startedAt = new Date().toISOString();
  state.runs[runId] = {
    id: runId,
    kind: 'CHAT',
    status: 'RUNNING',
    currentStage: null,
    attempt: 1,
    errorCode: null,
    errorMessage: null,
    startedAt,
    finishedAt: null,
    modelInvocations: [
      {
        id: nextId('invocation'),
        modelId: DEMO_MODEL_ID,
        modelKey: 'stub-fake-model',
        purpose: 'CHAT',
        status: 'STARTED',
        firstTokenMs: null,
        durationMs: null,
        promptTokens: null,
        completionTokens: null,
        errorCode: null,
        startedAt,
        finishedAt: null,
      },
    ],
    stages: [],
  };

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });

  let controllerClosed = false;
  const push = (event) => {
    if (controllerClosed) {
      return;
    }
    try {
      res.write(encodeEvent(event));
    } catch {
      controllerClosed = true;
    }
  };
  const close = () => {
    if (controllerClosed) {
      return;
    }
    controllerClosed = true;
    try {
      res.end();
    } catch {
      // 客户端已断开时无需再次结束响应。
    }
  };

  run.timer = setInterval(() => {
    const event = run.queue[run.cursor];
    run.cursor += 1;
    if (!event) {
      finishRun(run, 'COMPLETED');
      close();
      return;
    }
    if (event.eventType === 'riccon.chat.delta.v1') {
      run.content += event.data.delta;
    } else if (event.eventType === 'riccon.chat.citation.v1') {
      run.citations = [...run.citations, ...event.data.citations];
    }
    push(event);
    if (event.eventType === 'riccon.chat.completed.v1') {
      finishRun(run, 'COMPLETED');
      close();
    } else if (event.eventType === 'riccon.chat.failed.v1') {
      finishRun(run, 'FAILED');
      close();
    }
  }, DELTA_INTERVAL_MS);

  // 客户端 abort（用户停止或刷新）时保留已生成内容，并结束服务端运行。
  req.on('close', () => {
    if (!run.closed) {
      finishRun(run, 'STOPPED');
    }
    controllerClosed = true;
  });
}

/* -------------------------------------------------------------------------- */
/* J2 报告任务                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * 根据主题选择演示路径；关键词只服务 E2E 失败/重试场景。
 *
 * @param topic 调研主题
 * @returns success | tool-failure | generation-failure | no-sources
 */
function researchModeForTopic(topic) {
  if (/工具失败|超时/.test(topic)) {
    return 'tool-failure';
  }
  if (/生成失败/.test(topic)) {
    return 'generation-failure';
  }
  if (/无结果|没有资料/.test(topic)) {
    return 'no-sources';
  }
  return 'success';
}

/** 构造两条知识库命中。 */
function buildDemoSources() {
  return [
    {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 4,
      title: '知识库：2025 中国企业协同办公市场研究',
      snippet: '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%，头部厂商份额继续集中。',
      score: 0.92,
    },
    {
      ordinal: 2,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_TWO,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 9,
      title: '知识库：协同办公行业竞争格局摘录',
      snippet: '中腰部厂商通过行业解决方案与私有化部署差异化竞争，客户续费率是主要观察指标。',
      score: 0.84,
    },
  ];
}

/**
 * 构造审批记录。
 *
 * @param task 内部任务
 * @param attempt 工具阶段尝试号
 * @param now 当前毫秒时间
 * @returns 待审批记录
 */
function createResearchApproval(task, attempt, now) {
  const paramsPreview = {
    market: task.task.topic.slice(0, 60),
    region: '华东',
    period: '2025',
    fields: 'market_size,growth_rate',
  };
  return {
    id: nextId('approval'),
    researchTaskId: task.task.id,
    runId: task.task.currentRunId ?? nextId('run'),
    stage: 'TOOL',
    attempt,
    status: 'PENDING',
    actionType: 'MCP_TOOL_CALL',
    toolName: 'market_data_lookup',
    paramsPreview,
    paramsSha256: pseudoSha256(JSON.stringify(paramsPreview)),
    requestedBy: DEMO_USER_ID,
    decidedBy: null,
    decidedAt: null,
    decisionComment: null,
    expiresAt: new Date(now + 15 * 60 * 1000).toISOString(),
    createdAt: new Date(now).toISOString(),
  };
}

/**
 * 生成报告 Markdown。
 *
 * @param title 标题
 * @param sources 检索来源
 * @param invocation 成功的工具调用
 * @returns 报告正文
 */
function buildReportBody(title, sources, invocation) {
  const sourceTitles = sources.map((source) => `${source.title}（[${source.ordinal}]）`);
  return [
    `# ${title}`,
    '',
    '## 摘要',
    '本报告基于租户知识库检索与一次经人工审批的只读市场数据调用生成，结论仅用于演示，不代表真实市场数据。',
    '',
    '## 市场规模与增长',
    '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%[1]。',
    '- 头部厂商依靠生态与私有化交付能力保持份额',
    '- 中腰部厂商通过行业方案与数据工具提升续费率',
    '',
    '## 竞争格局',
    '竞争焦点从通用功能转向行业数据、权限隔离与可审计的 Agent 能力[2]。',
    invocation
      ? `MCP 工具 ${invocation.toolName} 返回的演示数据用于核对市场规模与增速[3]。`
      : '本次报告未使用外部工具数据。',
    '',
    '## 结论与建议',
    '建议先以部门级知识库和只读工具验证闭环，再逐步开放写操作与跨部门共享。',
    '',
    '## 数据来源',
    ...sourceTitles.map((item) => `- ${item}`),
    ...(invocation
      ? [`- MCP 工具 ${invocation.toolName}（耗时 ${invocation.durationMs ?? 0} ms）`]
      : []),
  ].join('\n');
}

/**
 * 确保任务只有一份产物；重试沿用同一产物，不重复递增版本。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function ensureResearchArtifact(task, now) {
  if (task.artifact) {
    return;
  }
  const invocation =
    [...task.invocations].reverse().find((item) => item.status === 'SUCCEEDED') ?? null;
  const title = `${task.task.topic} · 市场调研报告`.slice(0, 300);
  const body = buildReportBody(title, task.sources, invocation);
  const nowIso = new Date(now).toISOString();
  const citations = [
    ...task.sources.map((source) => ({
      ordinal: source.ordinal,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: source.knowledgeDocumentId ?? null,
      title: source.title,
      snippet: source.snippet,
      locatorType: source.locatorType ?? null,
      pageNo: source.pageNo ?? null,
      sheetName: source.sheetName ?? null,
      paragraphNo: source.paragraphNo ?? null,
    })),
    ...(invocation
      ? [
          {
            ordinal: task.sources.length + 1,
            sourceType: 'MCP',
            mcpInvocationId: invocation.id,
            title: `MCP：${invocation.toolName}`,
            snippet: '返回 3 条市场规模与增速记录（演示数据）。',
            locatorType: null,
            pageNo: null,
            sheetName: null,
            paragraphNo: null,
          },
        ]
      : []),
  ];
  const artifactId = nextId('artifact');
  task.artifact = {
    id: artifactId,
    workspaceId: task.task.workspaceId,
    researchTaskId: task.task.id,
    kind: 'REPORT',
    title,
    status: 'SAVED',
    currentVersion: 1,
    createdAt: nowIso,
    updatedAt: nowIso,
    currentVersionDetail: {
      id: nextId('artifact-version'),
      version: 1,
      contentType: 'text/markdown',
      sizeBytes: Math.max(1, body.length),
      sha256: pseudoSha256(body),
      summary: '报告基于知识库检索与经审批的市场数据工具生成，覆盖市场规模、竞争格局与建议。',
      createdAt: nowIso,
      citations,
    },
  };
  task.artifactBody = body;
}

/**
 * 完成检索阶段；无结果时任务失败且不进入生成。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchRetrieval(task, now) {
  const nowIso = new Date(now).toISOString();
  const record = task.stages.find((stage) => stage.stage === 'RETRIEVAL' && stage.attempt === 1);
  if (record) {
    record.finishedAt = nowIso;
  }
  if (task.mode === 'no-sources') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-RESEARCH-404';
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-RESEARCH-404';
    task.task.failureMessage = '未检索到相关资料，请调整主题后重新创建任务。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }
  if (record) {
    record.status = 'SUCCEEDED';
    record.details = { hitCount: 2 };
  }
  task.sources = buildDemoSources();
  task.task.status = 'WAITING_APPROVAL';
  task.task.currentStage = 'APPROVAL';
  task.approvals.push(createResearchApproval(task, 1, now));
  task.phase = 'waiting-approval';
  task.phaseStartedAtMs = now;
}

/**
 * 完成工具阶段；成功进入生成，失败保留记录并标记任务失败。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchTool(task, now) {
  const nowIso = new Date(now).toISOString();
  const invocation = task.invocations.find((item) => item.status === 'STARTED');
  const record = task.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (task.mode === 'tool-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MCP-504';
      record.finishedAt = nowIso;
    }
    if (invocation) {
      invocation.status = 'FAILED';
      invocation.errorCode = 'RICCON-MCP-504';
      invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
      invocation.finishedAt = nowIso;
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-MCP-504';
    task.task.failureMessage = '外部数据工具响应超时（合成），请重试工具阶段。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
    record.details = {
      ...(record.details ?? {}),
      resultSummary: '返回 3 条市场规模与增速记录（演示数据）。',
    };
  }
  if (invocation) {
    invocation.status = 'SUCCEEDED';
    invocation.resultSha256 = pseudoSha256('demo-market-data-result');
    invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
    invocation.finishedAt = nowIso;
  }
  task.task.status = 'RUNNING';
  task.task.currentStage = 'GENERATION';
  const attempt = task.stages.filter((stage) => stage.stage === 'GENERATION').length + 1;
  task.stages.push({
    stage: 'GENERATION',
    attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: null,
  });
  task.phase = 'generation';
  task.phaseStartedAtMs = now;
}

/**
 * 完成生成阶段；失败保留阶段记录，成功产出唯一产物。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchGeneration(task, now) {
  const nowIso = new Date(now).toISOString();
  const record = task.stages
    .filter((stage) => stage.stage === 'GENERATION' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (task.mode === 'generation-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MODEL-500';
      record.finishedAt = nowIso;
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-MODEL-500';
    task.task.failureMessage = '报告生成失败（合成），已完成阶段记录已保留。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
  }
  ensureResearchArtifact(task, now);
  task.task.status = 'SUCCEEDED';
  task.task.currentStage = null;
  task.task.finishedAt = nowIso;
  task.phase = 'done';
}

/**
 * 按真实时间推进所有任务；每次读取前调用，循环补完多个已到期阶段。
 */
function advanceResearchTasks() {
  const now = Date.now();
  for (const task of state.researchTasks) {
    let progressed = true;
    while (progressed) {
      progressed = false;
      if (task.phase === 'retrieval' && now - task.phaseStartedAtMs >= RESEARCH_RETRIEVAL_MS) {
        completeResearchRetrieval(task, now);
        progressed = true;
      } else if (task.phase === 'tool' && now - task.phaseStartedAtMs >= RESEARCH_TOOL_MS) {
        completeResearchTool(task, now);
        progressed = true;
      } else if (
        task.phase === 'generation' &&
        now - task.phaseStartedAtMs >= RESEARCH_GENERATION_MS
      ) {
        completeResearchGeneration(task, now);
        progressed = true;
      }
    }
  }
}

/** 构造任务摘要。 */
function researchTaskSummary(task) {
  return { ...task.task };
}

/** 阶段排序：有开始时间在前，其后按尝试号。 */
function compareStages(left, right) {
  const leftTime = left.startedAt ? Date.parse(left.startedAt) : Number.MAX_SAFE_INTEGER;
  const rightTime = right.startedAt ? Date.parse(right.startedAt) : Number.MAX_SAFE_INTEGER;
  if (leftTime !== rightTime) {
    return leftTime - rightTime;
  }
  return left.attempt - right.attempt;
}

/** 构造任务详情；只暴露契约字段。 */
function researchTaskDetail(task) {
  return {
    ...task.task,
    stages: [...task.stages].sort(compareStages),
    approvals: [...task.approvals].sort(
      (left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
    ),
    mcpInvocations: [...task.invocations].sort(
      (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
    ),
    sources: [...task.sources],
    artifact: task.artifact,
  };
}

/** 查找任务。 */
function findResearchTask(taskId) {
  return state.researchTasks.find((item) => item.task.id === taskId);
}

/**
 * 创建报告任务。
 *
 * @param body 请求体
 * @param idempotencyKey 幂等键
 * @returns 响应
 */
function createResearchTask(body, idempotencyKey) {
  if (idempotencyKey.length < 8 || idempotencyKey.length > 128) {
    return problem(
      400,
      'RICCON-RESEARCH-400',
      '缺少幂等键',
      '创建报告任务必须带有效的 Idempotency-Key。',
    );
  }
  const idempotencyKeyForUser = `${DEMO_USER_ID}:${idempotencyKey}`;
  const existingId = state.researchIdempotency[idempotencyKeyForUser];
  if (existingId) {
    const existing = findResearchTask(existingId);
    if (existing) {
      return json(researchTaskSummary(existing), 201);
    }
  }

  const topic = typeof body?.topic === 'string' ? body.topic.trim() : '';
  const mcpToolId = typeof body?.mcpToolId === 'string' ? body.mcpToolId : '';
  if (topic.length < 4 || topic.length > 500) {
    return problem(400, 'RICCON-RESEARCH-400', '主题不合法', '调研主题需为 4–500 个字符。');
  }
  const tool = state.mcpTools.find((item) => item.id === mcpToolId && item.status === 'ACTIVE');
  if (!tool) {
    return problem(403, 'RICCON-MCP-403', '工具不可用', '所选 MCP 工具未授权或已停用。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  const taskId = nextId('research');
  const task = {
    task: {
      id: taskId,
      workspaceId: state.workspace.id,
      topic,
      status: 'RUNNING',
      currentStage: 'RETRIEVAL',
      mcpToolId,
      currentRunId: nextId('run'),
      failureCode: null,
      failureMessage: null,
      createdAt: nowIso,
      startedAt: nowIso,
      finishedAt: null,
    },
    mode: researchModeForTopic(topic),
    phase: 'retrieval',
    phaseStartedAtMs: now,
    stages: [
      {
        stage: 'RETRIEVAL',
        attempt: 1,
        status: 'RUNNING',
        startedAt: nowIso,
        finishedAt: null,
        details: { query: topic.slice(0, 80) },
      },
    ],
    approvals: [],
    invocations: [],
    sources: [],
    artifact: null,
    artifactBody: null,
  };
  state.researchTasks.unshift(task);
  state.researchIdempotency[idempotencyKeyForUser] = taskId;
  return json(researchTaskSummary(task), 201);
}

/**
 * 应用审批决定。
 *
 * @param approvalId 审批 ID
 * @param decision APPROVE | REJECT
 * @param comment 备注
 * @returns 响应
 */
function decideResearchApproval(approvalId, decision, comment) {
  advanceResearchTasks();
  const task = state.researchTasks.find((item) =>
    item.approvals.some((approval) => approval.id === approvalId),
  );
  const approval = task?.approvals.find((item) => item.id === approvalId);
  if (!task || !approval) {
    return problem(404, 'RICCON-TENANT-404', '审批不存在', '审批不存在或已被处理。');
  }
  if (approval.status !== 'PENDING') {
    const alreadyDecided = approval.status === (decision === 'APPROVE' ? 'APPROVED' : 'REJECTED');
    return alreadyDecided
      ? json(approval)
      : problem(409, 'RICCON-APPROVAL-409', '审批已处理', '该审批已作出相反决定。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  approval.status = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
  approval.decidedBy = DEMO_USER_ID;
  approval.decidedAt = nowIso;
  approval.decisionComment = comment;

  if (decision === 'REJECT') {
    task.stages.push({
      stage: 'APPROVAL',
      attempt: approval.attempt,
      status: 'FAILED',
      errorCode: 'RICCON-APPROVAL-REJECTED',
      startedAt: approval.createdAt,
      finishedAt: nowIso,
      details: null,
    });
    task.stages.push({
      stage: 'TOOL',
      attempt: approval.attempt,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    task.stages.push({
      stage: 'GENERATION',
      attempt: 1,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    task.task.status = 'CANCELLED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-APPROVAL-REJECTED';
    task.task.failureMessage = '审批被拒绝，任务已终止，未生成报告。';
    task.task.finishedAt = nowIso;
    task.phase = 'cancelled';
    return json(approval);
  }

  task.stages.push({
    stage: 'APPROVAL',
    attempt: approval.attempt,
    status: 'SUCCEEDED',
    startedAt: approval.createdAt,
    finishedAt: nowIso,
    details: { approvalId: approval.id },
  });
  task.stages.push({
    stage: 'TOOL',
    attempt: approval.attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: { toolName: approval.toolName, paramsPreview: approval.paramsPreview },
  });
  const invocationId = nextId('invocation');
  const runningToolRecord = task.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];
  if (runningToolRecord?.details) {
    runningToolRecord.details.invocationId = invocationId;
  }
  task.invocations.push({
    id: invocationId,
    toolName: approval.toolName,
    status: 'STARTED',
    paramsSha256: approval.paramsSha256,
    resultSha256: null,
    durationMs: null,
    errorCode: null,
    startedAt: nowIso,
    finishedAt: null,
  });
  task.task.status = 'RUNNING';
  task.task.currentStage = 'TOOL';
  task.task.failureCode = null;
  task.task.failureMessage = null;
  task.task.finishedAt = null;
  task.phase = 'tool';
  task.phaseStartedAtMs = now;
  return json(approval);
}

/**
 * 重试失败阶段。
 *
 * @param taskId 任务 ID
 * @param fromStage 请求阶段
 * @returns 响应
 */
function retryResearchTask(taskId, fromStage) {
  advanceResearchTasks();
  const task = findResearchTask(taskId);
  if (!task) {
    return problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。');
  }
  if (task.task.status !== 'FAILED') {
    return problem(409, 'RICCON-RESEARCH-409', '任务不可重试', '只有失败任务可以重试。');
  }
  const lastFailedStage = [...task.stages]
    .reverse()
    .find((stage) => stage.status === 'FAILED')?.stage;
  const stage = fromStage ?? lastFailedStage ?? null;
  if (stage !== 'TOOL' && stage !== 'GENERATION') {
    return problem(
      409,
      'RICCON-RESEARCH-409',
      '阶段不可重试',
      '检索阶段失败需调整主题后重新创建任务。',
    );
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  task.task.status = stage === 'TOOL' ? 'WAITING_APPROVAL' : 'RUNNING';
  task.task.currentStage = stage === 'TOOL' ? 'APPROVAL' : 'GENERATION';
  task.task.failureCode = null;
  task.task.failureMessage = null;
  task.task.finishedAt = null;
  task.mode = 'success';

  if (stage === 'TOOL') {
    const attempt =
      Math.max(
        0,
        ...task.stages.filter((item) => item.stage === 'TOOL').map((item) => item.attempt),
      ) + 1;
    task.approvals.push(createResearchApproval(task, attempt, now));
    task.phase = 'waiting-approval';
    task.phaseStartedAtMs = now;
  } else {
    const attempt =
      Math.max(
        0,
        ...task.stages.filter((item) => item.stage === 'GENERATION').map((item) => item.attempt),
      ) + 1;
    task.stages.push({
      stage: 'GENERATION',
      attempt,
      status: 'RUNNING',
      startedAt: nowIso,
      finishedAt: null,
      details: null,
    });
    task.phase = 'generation';
    task.phaseStartedAtMs = now;
  }
  return json(researchTaskSummary(task), 202);
}

/* -------------------------------------------------------------------------- */
/* HTTP 路由与传输                                                             */
/* -------------------------------------------------------------------------- */

/**
 * 写响应。
 *
 * @param res Node 响应
 * @param result `{status, body, contentType}` 或 SSE 已自行写出
 */
function writeResult(res, result) {
  const payload = Buffer.from(JSON.stringify(result.body));
  res.writeHead(result.status, {
    'Content-Type': result.contentType ?? 'application/json; charset=utf-8',
    'Content-Length': payload.length,
    'Cache-Control': 'no-store',
  });
  res.end(payload);
}

/**
 * 读取请求体。
 *
 * @param req Node 请求
 * @returns 原始字节
 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      // 预留 multipart 头开销；超过上限直接拒绝，避免 E2E 误传超大文件占满内存。
      if (total > MAX_FILE_BYTES + 1024 * 1024) {
        reject(Object.assign(new Error('body too large'), { code: 'TOO_LARGE' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * 解析请求 URL。
 *
 * @param req Node 请求
 * @returns pathname 与查询参数
 */
function parseUrl(req) {
  return new URL(req.url ?? '/', `http://${req.headers.host ?? `${HOST}:${PORT}`}`);
}

/**
 * 处理控制端点；不属于产品 OpenAPI，供 Playwright 隔离用例与制造错误场景。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param pathname 路径
 * @param raw 请求体
 * @returns 是否已处理
 */
function handleControl(req, res, pathname, raw) {
  if (pathname === '/__stub__/health' && req.method === 'GET') {
    writeResult(res, json({ ok: true }));
    return true;
  }
  if (pathname === '/__stub__/reset' && req.method === 'POST') {
    resetAll();
    writeResult(res, json({ ok: true }));
    return true;
  }
  if (pathname === '/__stub__/scenario' && req.method === 'POST') {
    const body = readJson(raw);
    scenario = typeof body?.name === 'string' ? body.name : 'none';
    writeResult(res, json({ ok: true, scenario }));
    return true;
  }
  if (pathname === '/__stub__/m03-seed' && req.method === 'POST') {
    seedM03State();
    writeResult(res, json({ ok: true, departments: state.departments.length }));
    return true;
  }
  if (pathname === '/__stub__/revoke-sessions' && req.method === 'POST') {
    // 模拟会话在服务端失效：保留业务数据，只清空会话表。
    sessions.clear();
    writeResult(res, json({ ok: true }));
    return true;
  }
  return false;
}

/**
 * 处理预签名对象下载；对象地址是绝对 URL，因此需要 CORS。
 *
 * 覆盖 J1 文件下载与 J2 产物正文；文件内容为合成字节，仅验证预签名链路。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param pathname 路径
 * @returns 是否已处理
 */
function handleObject(req, res, pathname) {
  const fileMatch = /^\/objects\/files\/([^/]+)$/.exec(pathname);
  if (fileMatch && req.method === 'GET') {
    const file = state.files.find((item) => item.id === decodeURIComponent(fileMatch[1]));
    if (!file) {
      const result = problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。');
      const payload = Buffer.from(JSON.stringify(result.body));
      res.writeHead(404, {
        'Content-Type': result.contentType,
        'Content-Length': payload.length,
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      });
      res.end(payload);
      return true;
    }
    // 上传内容不落盘，下载返回可识别的合成字节；仅用于验证预签名下载链路。
    const payload = Buffer.from(`Riccon E2E stub file: ${file.originalName}\n`, 'utf8');
    res.writeHead(200, {
      'Content-Type': file.mimeType || 'application/octet-stream',
      'Content-Length': payload.length,
      'Content-Disposition': `attachment; filename="${encodeURIComponent(file.originalName)}"`,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(payload);
    return true;
  }

  const match = /^\/objects\/artifacts\/([^/]+)\/versions\/(\d+)\/content$/.exec(pathname);
  if (!match || req.method !== 'GET') {
    return false;
  }
  const artifactId = decodeURIComponent(match[1]);
  const version = Number(match[2]);
  const task = state.researchTasks.find(
    (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
  );
  if (!task?.artifactBody) {
    const result = problem(
      404,
      'RICCON-TENANT-404',
      '产物正文不存在',
      '产物正文不存在或已被删除。',
    );
    const payload = Buffer.from(JSON.stringify(result.body));
    res.writeHead(404, {
      'Content-Type': result.contentType,
      'Content-Length': payload.length,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(payload);
    return true;
  }
  const payload = Buffer.from(task.artifactBody, 'utf8');
  res.writeHead(200, {
    'Content-Type': 'text/markdown; charset=utf-8',
    'Content-Length': payload.length,
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  });
  res.end(payload);
  return true;
}

/**
 * 处理 API 路由。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param url 解析后的 URL
 * @param raw 请求体
 * @returns 是否已处理
 */

/**
 * 读取当前会话账号。
 *
 * @param session 会话
 * @returns 账号对象或 null
 */
function currentAccount(session) {
  return session?.account ?? null;
}

/**
 * 查找租户部门。
 *
 * @param tenantId 租户
 * @param departmentId 部门
 * @returns 部门或 undefined
 */
function findDepartment(tenantId, departmentId) {
  return state.departments.find(
    (department) => department.tenantId === tenantId && department.id === departmentId,
  );
}

/**
 * 统计部门当前启用成员数。
 *
 * @param departmentId 部门
 * @returns 成员数
 */
function departmentMemberCount(departmentId) {
  return state.users.filter(
    (user) => user.departmentId === departmentId && user.status === 'ACTIVE',
  ).length;
}

/**
 * 部门响应；只含组织元数据与展示名。
 *
 * @param department 部门
 * @returns Department
 */
function departmentResponse(department) {
  const manager = department.managerUserId
    ? state.users.find((user) => user.id === department.managerUserId)
    : null;
  return {
    id: department.id,
    name: department.name,
    status: department.status,
    managerUserId: department.managerUserId ?? null,
    managerDisplayName: manager?.displayName ?? null,
    memberCount: departmentMemberCount(department.id),
    createdAt: department.createdAt,
    updatedAt: department.updatedAt,
  };
}

/**
 * 部门成员响应；不复用 UserSummary，避免泄露邮箱、角色与最近登录。
 *
 * @param user 账号
 * @returns DepartmentMember
 */
function departmentMemberResponse(user) {
  return {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    status: user.status,
  };
}

/**
 * 按账号范围列出部门：普通员工只看本人部门，租户管理员看全部。
 *
 * @param account 当前账号
 * @returns 部门列表
 */
function scopedDepartments(account) {
  if (!account) {
    return [];
  }
  if (account.persona === 'tenant-admin') {
    return state.departments.filter((department) => department.tenantId === account.tenantId);
  }
  if (account.persona === 'platform-admin') {
    return [];
  }
  const user = state.users.find((item) => item.id === account.userId);
  return state.departments.filter(
    (department) =>
      department.tenantId === account.tenantId && department.id === user?.departmentId,
  );
}

/**
 * 判断账号是否可管理指定部门：租户管理员或该部门负责人。
 *
 * @param account 账号
 * @param department 部门
 * @returns 可管理返回 true
 */
function canManageDepartment(account, department) {
  if (!account || !department || department.tenantId !== account.tenantId) {
    return false;
  }
  return (
    account.persona === 'tenant-admin' || department.managerUserId === account.userId
  );
}

/**
 * 判断团队空间是否可被账号访问。
 *
 * @param account 账号
 * @param workspace 团队空间
 * @returns 可访问返回 true
 */
function canAccessTeamWorkspace(account, workspace) {
  if (!account || workspace.tenantId !== account.tenantId) {
    return false;
  }
  const user = state.users.find((item) => item.id === account.userId);
  if (!user?.departmentId) {
    return false;
  }
  if (workspace.visibility === 'TENANT') {
    return true;
  }
  if (workspace.departmentId !== user.departmentId) {
    return false;
  }
  const department = findDepartment(account.tenantId, user.departmentId);
  if (!department || department.status !== 'ACTIVE') {
    return false;
  }
  if (workspace.visibility === 'DEPT') {
    return true;
  }
  if (workspace.visibility === 'CUSTOM') {
    return (state.workspaceMembers[workspace.id] ?? []).includes(account.userId);
  }
  return false;
}

/**
 * 团队空间响应。
 *
 * @param workspace 团队空间
 * @returns ManagedWorkspace
 */
function workspaceResponse(workspace) {
  const department = workspace.departmentId
    ? state.departments.find((item) => item.id === workspace.departmentId)
    : null;
  return {
    id: workspace.id,
    kind: 'TEAM',
    name: workspace.name,
    description: workspace.description ?? null,
    ownerUserId: workspace.ownerUserId,
    departmentId: workspace.departmentId ?? null,
    departmentName: department?.name ?? null,
    visibility: workspace.visibility,
    status: workspace.status ?? 'ACTIVE',
    createdAt: workspace.createdAt,
    updatedAt: workspace.updatedAt,
  };
}

/**
 * 当前账号可见的团队空间。
 *
 * @param account 账号
 * @returns 团队空间列表
 */
function accessibleTeamWorkspaces(account) {
  return state.teamWorkspaces.filter((workspace) => canAccessTeamWorkspace(account, workspace));
}

/**
 * 当前账号可见的部门空间管理列表；负责人和租户管理员能看到本部门全部空间。
 *
 * @param account 账号
 * @param department 部门
 * @returns 空间列表
 */
function manageableDepartmentWorkspaces(account, department) {
  if (!canManageDepartment(account, department)) {
    return [];
  }
  return state.teamWorkspaces.filter(
    (workspace) =>
      workspace.tenantId === account.tenantId && workspace.departmentId === department.id,
  );
}

/**
 * 账号所属租户的启用账号列表。
 *
 * @param tenantId 租户
 * @returns 用户列表
 */
function tenantUsers(tenantId) {
  return state.users.filter(
    (user) => user.tenantId === tenantId && user.status === 'ACTIVE',
  );
}

/**
 * 播种 M03 演示数据：销售部/市场部、负责人、普通成员与一个部门空间。
 *
 * 只由 E2E 控制端点调用，不在 reset 默认状态中，避免影响 M02 用例。
 */
function seedM03State() {
  state.departments = [
    {
      id: M03_SALES_DEPARTMENT_ID,
      tenantId: M03_TENANT_ACME,
      name: '销售部',
      status: 'ACTIVE',
      managerUserId: M03_MANAGER_USER_ID,
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    {
      id: M03_MARKETING_DEPARTMENT_ID,
      tenantId: M03_TENANT_ACME,
      name: '市场部',
      status: 'ACTIVE',
      managerUserId: null,
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
  ];
  state.users = state.users.map((user) => {
    if (user.id === M03_MEMBER_USER_ID || user.id === M03_MANAGER_USER_ID) {
      return { ...user, departmentId: M03_SALES_DEPARTMENT_ID };
    }
    if (user.id === M03_OUTSIDER_USER_ID) {
      return { ...user, departmentId: M03_MARKETING_DEPARTMENT_ID };
    }
    return user;
  });
  state.teamWorkspaces = [
    {
      id: M03_SALES_WORKSPACE_ID,
      tenantId: M03_TENANT_ACME,
      name: '销售部项目空间',
      description: '销售部共享空间（E2E 合成数据）',
      ownerUserId: M03_MANAGER_USER_ID,
      departmentId: M03_SALES_DEPARTMENT_ID,
      visibility: 'DEPT',
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
  ];
  state.workspaceMembers = {};
}

function handleApi(req, res, url, raw) {
  const { pathname, searchParams } = url;
  const method = req.method ?? 'GET';

  if (pathname === '/api/v1/auth/csrf' && method === 'GET') {
    const { session, created, sid } = ensureSession(req);
    if (created) {
      res.setHeader(
        'Set-Cookie',
        `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax`,
      );
    }
    writeResult(res, json({ headerName: 'X-CSRF-TOKEN', token: session.csrfToken }));
    return true;
  }

  if (pathname === '/api/v1/auth/login' && method === 'POST') {
    const { session, created, sid } = ensureSession(req);
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const key = `${body?.tenantCode}/${body?.username}`;
    const account = M03_CREDENTIALS[key];
    if (!account || body?.password !== account.password) {
      writeResult(res, problem(401, 'RICCON-AUTH-001', '登录失败', '租户代码、账号或密码不正确。'));
      return true;
    }
    session.authenticated = true;
    session.account = account;
    session.csrfToken = `stub-csrf-${nextId('csrf')}`;
    if (created) {
      res.setHeader(
        'Set-Cookie',
        `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax`,
      );
    }
    writeResult(res, json(sessionInfo(session)));
    return true;
  }

  const session = readSession(req);
  if (pathname === '/api/v1/auth/session' && method === 'GET') {
    const authError = requireAuth(session);
    writeResult(res, authError ?? json(sessionInfo(session)));
    return true;
  }

  if (pathname === '/api/v1/auth/logout' && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    dropSession(req);
    res.writeHead(204, { 'Cache-Control': 'no-store' });
    res.end();
    return true;
  }

  const authError = requireAuth(session);
  if (authError) {
    writeResult(res, authError);
    return true;
  }

  // ---------- M03：平台部门管理 ----------
  const adminTenantMatch = /^\/api\/v1\/admin\/tenants\/([^/]+)\/departments(?:\/([^/]+))?(?:\/(disable|enable|members|manager))?(?:\/([^/]+))?$/.exec(
    pathname,
  );
  if (pathname === '/api/v1/admin/tenants' && method === 'GET') {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理租户。'));
      return true;
    }
    writeResult(res, json(page(state.tenants)));
    return true;
  }

  const adminUserListMatch = /^\/api\/v1\/admin\/tenants\/([^/]+)\/users$/.exec(pathname);
  if (adminUserListMatch && method === 'GET') {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理部门。'));
      return true;
    }
    const tenantId = decodeURIComponent(adminUserListMatch[1]);
    writeResult(
      res,
      json(
        page(
          tenantUsers(tenantId).map((user) => ({
            id: user.id,
            username: user.username,
            displayName: user.displayName,
            email: user.email ?? null,
            status: user.status,
            roles: user.roles,
            lastLoginAt: user.lastLoginAt ?? null,
            createdAt: user.createdAt,
          })),
        ),
      ),
    );
    return true;
  }

  if (adminTenantMatch) {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理部门。'));
      return true;
    }
    const tenantId = decodeURIComponent(adminTenantMatch[1]);
    const departmentId = adminTenantMatch[2] ? decodeURIComponent(adminTenantMatch[2]) : null;
    const action = adminTenantMatch[3] ?? null;
    const suffix = adminTenantMatch[4] ? decodeURIComponent(adminTenantMatch[4]) : null;
    const tenant = state.tenants.find((item) => item.id === tenantId);
    if (!tenant) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '租户不存在', '目标租户不存在。'));
      return true;
    }
    if (tenant.status !== 'ACTIVE') {
      writeResult(
        res,
        problem(409, 'RICCON-CONFLICT-409', '租户已暂停', '目标租户已暂停，部门管理暂不可用。'),
      );
      return true;
    }
    if (!departmentId && method === 'GET') {
      writeResult(
        res,
        json(
          page(
            state.departments
              .filter((department) => department.tenantId === tenantId)
              .sort(
                (left, right) =>
                  left.name.localeCompare(right.name, 'zh-CN') || left.id.localeCompare(right.id),
              )
              .map(departmentResponse),
          ),
        ),
      );
      return true;
    }
    if (!departmentId && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '部门名称不能为空。'));
        return true;
      }
      if (
        state.departments.some(
          (department) => department.tenantId === tenantId && department.name === name,
        )
      ) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门名称冲突', '同一租户内部门名称必须唯一。'),
        );
        return true;
      }
      const now = new Date().toISOString();
      const department = {
        id: `dept-${nextId('dept')}`,
        tenantId,
        name,
        status: 'ACTIVE',
        managerUserId: null,
        createdAt: now,
        updatedAt: now,
      };
      state.departments = [...state.departments, department];
      writeResult(res, json(departmentResponse(department), 201));
      return true;
    }

    const department = departmentId
      ? findDepartment(tenantId, departmentId)
      : null;
    if (!department) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在或不属于该租户。'),
      );
      return true;
    }

    if (!action && method === 'PATCH') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      if (department.status !== 'ACTIVE') {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门已停用', '停用部门需先启用后再改名。'),
        );
        return true;
      }
      const body = readJson(raw);
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '部门名称不能为空。'));
        return true;
      }
      if (
        state.departments.some(
          (item) =>
            item.tenantId === tenantId && item.id !== department.id && item.name === name,
        )
      ) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门名称冲突', '同一租户内部门名称必须唯一。'),
        );
        return true;
      }
      department.name = name;
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }

    if (!action && method === 'GET' && !departmentId) {
      writeResult(res, json(page([])));
      return true;
    }

    if ((action === 'disable' || action === 'enable') && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      department.status = action === 'disable' ? 'DISABLED' : 'ACTIVE';
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }

    if (action === 'members' && !suffix && method === 'GET') {
      const members = state.users
        .filter((user) => user.departmentId === department.id && user.status === 'ACTIVE')
        .map(departmentMemberResponse);
      writeResult(res, json(page(members)));
      return true;
    }

    if (action === 'members' && !suffix && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) => item.id === body?.userId && item.tenantId === tenantId,
      );
      if (!user) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '账号不存在', '账号不存在。'));
        return true;
      }
      if (user.departmentId) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '账号已属于部门', '同一账号只能属于一个部门。'),
        );
        return true;
      }
      user.departmentId = department.id;
      writeResult(res, json(departmentMemberResponse(user), 201));
      return true;
    }

    if (action === 'members' && suffix && method === 'DELETE') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      if (department.managerUserId === suffix) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '负责人不能移出', '请先移交负责人。'),
        );
        return true;
      }
      const user = state.users.find((item) => item.id === suffix);
      if (user?.departmentId === department.id) {
        user.departmentId = null;
      }
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return true;
    }

    if (action === 'manager' && method === 'PUT') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) =>
          item.id === body?.userId &&
          item.tenantId === tenantId &&
          item.departmentId === department.id &&
          item.status === 'ACTIVE',
      );
      if (!user) {
        writeResult(
          res,
          problem(404, 'RICCON-TENANT-404', '账号不存在', '负责人必须是本部门当前启用成员。'),
        );
        return true;
      }
      department.managerUserId = user.id;
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }
  }

  // ---------- M03：租户内部门只读与空间共享 ----------
  if (pathname === '/api/v1/departments' && method === 'GET') {
    const account = currentAccount(session);
    writeResult(res, json(page(scopedDepartments(account).map(departmentResponse))));
    return true;
  }

  const departmentMembersMatch = /^\/api\/v1\/departments\/([^/]+)\/members$/.exec(pathname);
  if (departmentMembersMatch && method === 'GET') {
    const account = currentAccount(session);
    const departmentId = decodeURIComponent(departmentMembersMatch[1]);
    const department = findDepartment(account?.tenantId, departmentId);
    const user = state.users.find((item) => item.id === account?.userId);
    const allowed =
      department &&
      (account.persona === 'tenant-admin' || user?.departmentId === departmentId);
    if (!allowed) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    const members = state.users
      .filter((item) => item.departmentId === departmentId && item.status === 'ACTIVE')
      .map(departmentMemberResponse);
    writeResult(res, json(page(members)));
    return true;
  }

  const departmentWorkspacesMatch = /^\/api\/v1\/departments\/([^/]+)\/workspaces$/.exec(pathname);
  if (departmentWorkspacesMatch) {
    const account = currentAccount(session);
    const departmentId = decodeURIComponent(departmentWorkspacesMatch[1]);
    const department = findDepartment(account?.tenantId, departmentId);
    if (!department) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    if (!canManageDepartment(account, department)) {
      const user = state.users.find((item) => item.id === account?.userId);
      if (user?.departmentId === departmentId) {
        writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人可以管理空间。'));
      } else {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      }
      return true;
    }
    if (department.status !== 'ACTIVE') {
      writeResult(res, problem(400, 'RICCON-REQUEST-400', '部门已停用', '停用部门不能管理空间。'));
      return true;
    }
    if (method === 'GET') {
      writeResult(
        res,
        json(page(manageableDepartmentWorkspaces(account, department).map(workspaceResponse))),
      );
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const visibility = body?.visibility ?? 'DEPT';
      if (visibility !== 'DEPT' && visibility !== 'CUSTOM') {
        writeResult(
          res,
          problem(400, 'RICCON-REQUEST-400', '可见性无效', '只能创建本部门或指定成员空间。'),
        );
        return true;
      }
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '空间名称不能为空。'));
        return true;
      }
      const now = new Date().toISOString();
      const workspace = {
        id: nextId('workspace'),
        tenantId: account.tenantId,
        name,
        description: typeof body?.description === 'string' ? body.description : null,
        ownerUserId: account.userId,
        departmentId,
        visibility,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
      };
      state.teamWorkspaces = [...state.teamWorkspaces, workspace];
      writeResult(res, json(workspaceResponse(workspace), 201));
      return true;
    }
  }

  if (pathname === '/api/v1/workspaces' && method === 'GET') {
    const account = currentAccount(session);
    const kind = searchParams.get('kind');
    const items =
      kind === 'PERSONAL'
        ? [state.workspace]
        : kind === 'TEAM'
          ? accessibleTeamWorkspaces(account).map(workspaceResponse)
          : [state.workspace, ...accessibleTeamWorkspaces(account).map(workspaceResponse)];
    writeResult(res, json(page(items)));
    return true;
  }

  if (pathname === '/api/v1/workspaces' && method === 'POST') {
    const account = currentAccount(session);
    if (account?.persona !== 'tenant-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以创建空间。'));
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const department = findDepartment(account.tenantId, body?.departmentId);
    if (!department || department.status !== 'ACTIVE') {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    const visibility = body?.visibility ?? 'DEPT';
    if (visibility !== 'DEPT' && visibility !== 'CUSTOM') {
      writeResult(res, problem(400, 'RICCON-REQUEST-400', '可见性无效', '可见性无效。'));
      return true;
    }
    const now = new Date().toISOString();
    const workspace = {
      id: nextId('workspace'),
      tenantId: account.tenantId,
      name: typeof body?.name === 'string' ? body.name.trim() : '',
      description: typeof body?.description === 'string' ? body.description : null,
      ownerUserId: account.userId,
      departmentId: department.id,
      visibility,
      status: 'ACTIVE',
      createdAt: now,
      updatedAt: now,
    };
    state.teamWorkspaces = [...state.teamWorkspaces, workspace];
    writeResult(res, json(workspaceResponse(workspace), 201));
    return true;
  }

  const workspaceMatch = /^\/api\/v1\/workspaces\/([^/]+)$/.exec(pathname);
  if (workspaceMatch && (method === 'GET' || method === 'PATCH')) {
    const account = currentAccount(session);
    const workspaceId = decodeURIComponent(workspaceMatch[1]);
    const workspace = state.teamWorkspaces.find(
      (item) => item.id === workspaceId && item.tenantId === account?.tenantId,
    );
    if (!workspace) {
      if (workspaceId === state.workspace.id) {
        writeResult(res, json(state.workspace));
        return true;
      }
      writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
      return true;
    }
    if (method === 'GET') {
      if (!canAccessTeamWorkspace(account, workspace)) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
        return true;
      }
      writeResult(res, json(workspaceResponse(workspace)));
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const department = findDepartment(account.tenantId, workspace.departmentId);
    if (!canManageDepartment(account, department)) {
      writeResult(
        res,
        canAccessTeamWorkspace(account, workspace)
          ? problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人或租户管理员可以修改空间。')
          : problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'),
      );
      return true;
    }
    const body = readJson(raw);
    if (typeof body?.name === 'string' && body.name.trim()) {
      workspace.name = body.name.trim();
    }
    if (typeof body?.description === 'string') {
      workspace.description = body.description;
    }
    if (typeof body?.visibility === 'string') {
      if (body.visibility !== 'DEPT' && body.visibility !== 'CUSTOM') {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '可见性无效', '可见性无效。'));
        return true;
      }
      if (body.visibility !== workspace.visibility) {
        workspace.visibility = body.visibility;
        state.workspaceMembers[workspace.id] = [];
      }
    }
    workspace.updatedAt = new Date().toISOString();
    writeResult(res, json(workspaceResponse(workspace)));
    return true;
  }

  const workspaceMembersMatch = /^\/api\/v1\/workspaces\/([^/]+)\/members(?:\/([^/]+))?$/.exec(
    pathname,
  );
  if (workspaceMembersMatch) {
    const account = currentAccount(session);
    const workspaceId = decodeURIComponent(workspaceMembersMatch[1]);
    const targetUserId = workspaceMembersMatch[2]
      ? decodeURIComponent(workspaceMembersMatch[2])
      : null;
    const workspace = state.teamWorkspaces.find(
      (item) => item.id === workspaceId && item.tenantId === account?.tenantId,
    );
    if (!workspace) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
      return true;
    }
    const department = findDepartment(account.tenantId, workspace.departmentId);
    if (!canManageDepartment(account, department)) {
      writeResult(
        res,
        canAccessTeamWorkspace(account, workspace)
          ? problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人或租户管理员可以管理成员。')
          : problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'),
      );
      return true;
    }
    if (!targetUserId && method === 'GET') {
      if (workspace.visibility !== 'CUSTOM') {
        writeResult(res, json(page([])));
        return true;
      }
      const members = (state.workspaceMembers[workspace.id] ?? [])
        .map((userId) => state.users.find((user) => user.id === userId))
        .filter(Boolean)
        .map((user) => ({
          userId: user.id,
          username: user.username,
          displayName: user.displayName,
        }));
      writeResult(res, json(page(members)));
      return true;
    }
    if (!targetUserId && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) =>
          item.id === body?.userId &&
          item.tenantId === account.tenantId &&
          item.departmentId === workspace.departmentId &&
          item.status === 'ACTIVE',
      );
      if (!user) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '账号不存在', '账号不存在。'));
        return true;
      }
      const existing = state.workspaceMembers[workspace.id] ?? [];
      if (existing.includes(user.id)) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '成员已存在', '该账号已是空间显式成员。'),
        );
        return true;
      }
      state.workspaceMembers[workspace.id] = [...existing, user.id];
      writeResult(
        res,
        json({ userId: user.id, username: user.username, displayName: user.displayName }, 201),
      );
      return true;
    }
    if (targetUserId && method === 'DELETE') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      state.workspaceMembers[workspace.id] = (state.workspaceMembers[workspace.id] ?? []).filter(
        (userId) => userId !== targetUserId,
      );
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return true;
    }
  }

  // 场景：报告任务列表返回 403，用于验证页面无权限态。
  const researchTasksMatch = /^\/api\/v1\/workspaces\/([^/]+)\/research-tasks$/.exec(pathname);
  if (researchTasksMatch) {
    if (decodeURIComponent(researchTasksMatch[1]) !== state.workspace.id) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'GET') {
      if (scenario === 'research-list-403') {
        writeResult(res, problem(403, 'RICCON-PERM-403', '没有权限', '当前账号无权查看报告任务。'));
        return true;
      }
      advanceResearchTasks();
      const status = searchParams.get('status');
      const items = state.researchTasks
        .filter((task) => !status || task.task.status === status)
        .map(researchTaskSummary)
        .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
      writeResult(res, json(page(items)));
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      writeResult(res, createResearchTask(readJson(raw), req.headers['idempotency-key'] ?? ''));
      return true;
    }
  }

  const researchTaskMatch = /^\/api\/v1\/research-tasks\/([^/]+)$/.exec(pathname);
  if (researchTaskMatch && method === 'GET') {
    advanceResearchTasks();
    const task = findResearchTask(decodeURIComponent(researchTaskMatch[1]));
    writeResult(
      res,
      task
        ? json(researchTaskDetail(task))
        : problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。'),
    );
    return true;
  }

  const researchRetryMatch = /^\/api\/v1\/research-tasks\/([^/]+)\/retry$/.exec(pathname);
  if (researchRetryMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const fromStage = readJson(raw)?.fromStage;
    if (
      fromStage !== undefined &&
      fromStage !== null &&
      fromStage !== 'TOOL' &&
      fromStage !== 'GENERATION'
    ) {
      writeResult(
        res,
        problem(400, 'RICCON-RESEARCH-400', '阶段不合法', '仅允许重试 TOOL 或 GENERATION。'),
      );
      return true;
    }
    writeResult(
      res,
      retryResearchTask(decodeURIComponent(researchRetryMatch[1]), fromStage ?? null),
    );
    return true;
  }

  const runDetailMatch = /^\/api\/v1\/runs\/([^/]+)$/.exec(pathname);
  if (runDetailMatch && method === 'GET') {
    const detail = state.runs[decodeURIComponent(runDetailMatch[1])];
    writeResult(
      res,
      detail
        ? json(detail)
        : problem(404, 'RICCON-RUN-404', '运行不存在', '运行不存在或已被删除。'),
    );
    return true;
  }

  if (pathname === '/api/v1/mcp-tools' && method === 'GET') {
    if (scenario === 'mcp-tools-403') {
      writeResult(
        res,
        problem(403, 'RICCON-PERM-403', '没有权限', '当前账号无权使用 MCP 数据工具。'),
      );
      return true;
    }
    writeResult(res, json(page(state.mcpTools.filter((tool) => tool.status === 'ACTIVE'))));
    return true;
  }

  const approvalDecisionMatch = /^\/api\/v1\/approvals\/([^/]+)\/decision$/.exec(pathname);
  if (approvalDecisionMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const decision = body?.decision;
    if (decision !== 'APPROVE' && decision !== 'REJECT') {
      writeResult(
        res,
        problem(400, 'RICCON-APPROVAL-400', '决定不合法', '审批决定必须为 APPROVE 或 REJECT。'),
      );
      return true;
    }
    const rawComment = typeof body?.comment === 'string' ? body.comment.trim() : '';
    if (rawComment.length > 500) {
      writeResult(
        res,
        problem(400, 'RICCON-APPROVAL-400', '备注过长', '审批备注不能超过 500 个字符。'),
      );
      return true;
    }
    // 场景：强制返回 409，验证前端冲突提示与刷新逻辑；审批保持待处理。
    if (scenario === 'approval-409') {
      writeResult(
        res,
        problem(409, 'RICCON-APPROVAL-409', '审批已处理', '该审批已被其他会话处理。'),
      );
      return true;
    }
    writeResult(
      res,
      decideResearchApproval(
        decodeURIComponent(approvalDecisionMatch[1]),
        decision,
        rawComment || null,
      ),
    );
    return true;
  }

  const artifactDownloadMatch =
    /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)\/download-url$/.exec(pathname);
  if (artifactDownloadMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactDownloadMatch[1]);
    const version = Number(artifactDownloadMatch[2]);
    const task = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!task?.artifact) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物或版本不存在。'));
      return true;
    }
    writeResult(
      res,
      json({
        // 绝对地址模拟对象存储预签名 URL；对象路由返回 CORS 头，客户端以 credentials: omit 读取。
        url: `http://${req.headers.host}/objects/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/content`,
        expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      }),
    );
    return true;
  }

  const artifactContentMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)\/content$/.exec(
    pathname,
  );
  if (artifactContentMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactContentMatch[1]);
    const version = Number(artifactContentMatch[2]);
    const task = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!task?.artifact || !task.artifactBody) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物或版本不存在。'));
      return true;
    }
    // 同源内容端点：模拟 API 代理对象存储，浏览器不直接访问预签名地址。
    const payload = Buffer.from(task.artifactBody, 'utf8');
    res.writeHead(200, {
      'Content-Type':
        task.artifact.currentVersionDetail?.contentType ?? 'text/markdown; charset=utf-8',
      'Content-Length': payload.length,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(payload);
    return true;
  }

  const artifactsMatch = /^\/api\/v1\/workspaces\/([^/]+)\/artifacts$/.exec(pathname);
  if (artifactsMatch && method === 'GET') {
    if (decodeURIComponent(artifactsMatch[1]) !== state.workspace.id) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    advanceResearchTasks();
    const items = state.researchTasks
      .map((task) => task.artifact)
      .filter(Boolean)
      .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
    writeResult(res, json(page(items)));
    return true;
  }

  const artifactMatch = /^\/api\/v1\/artifacts\/([^/]+)$/.exec(pathname);
  if (artifactMatch && method === 'GET') {
    advanceResearchTasks();
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactMatch[1]));
    writeResult(
      res,
      artifact
        ? json(artifact)
        : problem(404, 'RICCON-TENANT-404', '产物不存在', '产物不存在或已被删除。'),
    );
    return true;
  }

  const artifactVersionsMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions$/.exec(pathname);
  if (artifactVersionsMatch && method === 'GET') {
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactVersionsMatch[1]));
    if (!artifact) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物不存在或已被删除。'));
      return true;
    }
    writeResult(
      res,
      json(page(artifact.currentVersionDetail ? [artifact.currentVersionDetail] : [])),
    );
    return true;
  }

  const artifactVersionMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)$/.exec(pathname);
  if (artifactVersionMatch && method === 'GET') {
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactVersionMatch[1]));
    const version = artifact?.currentVersionDetail;
    if (!artifact || !version || version.version !== Number(artifactVersionMatch[2])) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物版本不存在', '产物或版本不存在。'));
      return true;
    }
    writeResult(res, json(version));
    return true;
  }

  const conversationsMatch = /^\/api\/v1\/workspaces\/([^/]+)\/conversations$/.exec(pathname);
  if (conversationsMatch) {
    const workspaceId = decodeURIComponent(conversationsMatch[1]);
    if (workspaceId !== state.workspace.id) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'GET') {
      const items = [...state.conversations].sort(
        (left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt),
      );
      writeResult(res, json(page(items)));
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const now = new Date().toISOString();
      const conversation = {
        id: nextId('conversation'),
        workspaceId,
        title: typeof body?.title === 'string' && body.title.trim() ? body.title.trim() : null,
        status: 'ACTIVE',
        lastMessageAt: null,
        createdAt: now,
        updatedAt: now,
      };
      state.conversations = [conversation, ...state.conversations];
      state.messages[conversation.id] = [];
      writeResult(res, json(conversation, 201));
      return true;
    }
  }

  const conversationMatch = /^\/api\/v1\/conversations\/([^/]+)$/.exec(pathname);
  if (conversationMatch && method === 'GET') {
    const conversationId = decodeURIComponent(conversationMatch[1]);
    const conversation = state.conversations.find((item) => item.id === conversationId);
    if (!conversation) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
      return true;
    }
    writeResult(res, json({ ...conversation, messages: state.messages[conversationId] ?? [] }));
    return true;
  }

  const messagesMatch = /^\/api\/v1\/conversations\/([^/]+)\/messages$/.exec(pathname);
  if (messagesMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const conversation = state.conversations.find(
      (item) => item.id === decodeURIComponent(messagesMatch[1]),
    );
    if (!conversation) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
      return true;
    }
    handleSendMessage(req, res, conversation, raw);
    return true;
  }

  const stopMatch = /^\/api\/v1\/conversations\/([^/]+)\/stop$/.exec(pathname);
  if (stopMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const runId = readJson(raw)?.runId;
    const run = typeof runId === 'string' ? activeRuns.get(runId) : undefined;
    // 停止是幂等操作：运行已结束时仍返回 202，避免客户端 abort 与 stop 请求竞速产生噪声错误。
    if (run) {
      finishRun(run, 'STOPPED');
    }
    writeResult(res, json({ stopped: true }, 202));
    return true;
  }

  const filesMatch = /^\/api\/v1\/workspaces\/([^/]+)\/files$/.exec(pathname);
  if (filesMatch) {
    const workspaceId = decodeURIComponent(filesMatch[1]);
    if (workspaceId !== state.workspace.id) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const parsed = parseMultipart(raw, req.headers['content-type']);
      const uploaded = parsed?.files.file;
      const conversationId = parsed?.fields.conversationId ?? '';
      if (!uploaded) {
        writeResult(res, problem(400, 'RICCON-FILE-400', '上传请求无效', '缺少文件。'));
        return true;
      }
      const conversation = state.conversations.find((item) => item.id === conversationId);
      if (!conversation) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
        return true;
      }
      advanceParses();
      const existing = state.files.filter(
        (item) => item.conversationId === conversationId && item.status !== 'DELETED',
      );
      if (existing.length >= MAX_FILES_PER_CONVERSATION) {
        writeResult(
          res,
          problem(409, 'RICCON-FILE-409', '文件数量已达上限', '每个会话最多上传 5 个文件。'),
        );
        return true;
      }
      if (uploaded.data.length > MAX_FILE_BYTES) {
        writeResult(res, problem(413, 'RICCON-FILE-413', '文件过大', '单文件不能超过 200 MB。'));
        return true;
      }
      if (uploaded.data.length === 0) {
        writeResult(res, problem(415, 'RICCON-FILE-415', '不支持的文件', '文件为空。'));
        return true;
      }
      const format = formatForFile(uploaded.filename, uploaded.contentType);
      if (!format) {
        writeResult(
          res,
          problem(
            415,
            'RICCON-FILE-415',
            '不支持的文件',
            '仅支持 PDF、DOCX、XLSX、PNG、JPEG，且扩展名与类型必须一致。',
          ),
        );
        return true;
      }

      const now = new Date().toISOString();
      const fileId = nextId('file');
      const file = {
        id: fileId,
        workspaceId,
        conversationId,
        originalName: uploaded.filename,
        format,
        mimeType: uploaded.contentType,
        sizeBytes: uploaded.data.length,
        sha256: pseudoSha256(`${uploaded.filename}:${uploaded.data.length}`),
        status: 'AVAILABLE',
        parse: {
          id: nextId('parse'),
          attempt: 1,
          status: 'PENDING',
          parserVersion: 'stub-parser-1',
          pageCount: null,
          sheetCount: null,
          chunkCount: null,
          errorCode: null,
          errorMessage: null,
          startedAt: now,
          finishedAt: null,
        },
        createdAt: now,
        deletedAt: null,
      };
      state.files.push(file);
      // 文件名包含 fail/损坏/失败 时首次解析失败，便于覆盖重试路径。
      state.parseOutcomes[fileId] = { fail: /fail|损坏|失败/i.test(uploaded.filename) };
      conversation.updatedAt = now;
      writeResult(res, json(file, 201));
      return true;
    }
    if (method === 'GET') {
      advanceParses();
      const conversationId = searchParams.get('conversationId');
      const items = state.files
        .filter((file) => file.status !== 'DELETED')
        .filter((file) => !conversationId || file.conversationId === conversationId)
        .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
      writeResult(res, json(page(items)));
      return true;
    }
  }

  const fileMatch = /^\/api\/v1\/files\/([^/]+)$/.exec(pathname);
  if (fileMatch && method === 'GET') {
    advanceParses();
    const file = state.files.find((item) => item.id === decodeURIComponent(fileMatch[1]));
    writeResult(
      res,
      file ? json(file) : problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'),
    );
    return true;
  }

  const retryParseMatch = /^\/api\/v1\/files\/([^/]+)\/parse-retries$/.exec(pathname);
  if (retryParseMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const file = state.files.find((item) => item.id === decodeURIComponent(retryParseMatch[1]));
    if (!file) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'));
      return true;
    }
    const now = new Date().toISOString();
    file.parse = {
      id: nextId('parse'),
      attempt: (file.parse?.attempt ?? 0) + 1,
      status: 'PENDING',
      parserVersion: 'stub-parser-1',
      pageCount: null,
      sheetCount: null,
      chunkCount: null,
      errorCode: null,
      errorMessage: null,
      startedAt: now,
      finishedAt: null,
    };
    // 重试会成功，便于验证失败 → 恢复路径。
    state.parseOutcomes[file.id] = { fail: false };
    writeResult(res, json(file, 202));
    return true;
  }

  const downloadMatch = /^\/api\/v1\/files\/([^/]+)\/download-url$/.exec(pathname);
  if (downloadMatch && method === 'GET') {
    const file = state.files.find((item) => item.id === decodeURIComponent(downloadMatch[1]));
    if (!file) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'));
      return true;
    }
    writeResult(
      res,
      json({
        url: `http://${req.headers.host}/objects/files/${encodeURIComponent(file.id)}?token=stub`,
        expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      }),
    );
    return true;
  }

  return false;
}

/** HTTP 服务：控制端点 → 对象下载 → API；未匹配返回 404 ProblemDetail。 */
const server = http.createServer((req, res) => {
  const url = parseUrl(req);
  readBody(req)
    .then((raw) => {
      try {
        if (handleControl(req, res, url.pathname, raw)) {
          return;
        }
        if (handleObject(req, res, url.pathname)) {
          return;
        }
        if (handleApi(req, res, url, raw)) {
          return;
        }
        writeResult(res, problem(404, 'RICCON-NOT-FOUND', '接口不存在', 'stub 未实现该接口。'));
      } catch (error) {
        // stub 内部异常要显式暴露给 E2E，不能静默成功。
        console.error('[stub] 处理请求失败', req.method, url.pathname, error);
        writeResult(res, problem(500, 'RICCON-STUB-500', 'stub 内部错误', String(error)));
      }
    })
    .catch((error) => {
      if (error?.code === 'TOO_LARGE') {
        writeResult(res, problem(413, 'RICCON-FILE-413', '文件过大', '单文件不能超过 200 MB。'));
        return;
      }
      console.error('[stub] 读取请求体失败', error);
      try {
        writeResult(res, problem(500, 'RICCON-STUB-500', 'stub 内部错误', String(error)));
      } catch {
        res.destroy();
      }
    });
});

server.listen(PORT, HOST, () => {
  console.log(`[stub] Riccon E2E stub API listening on http://${HOST}:${PORT}`);
});
