// 真实栈验证用 HTTP 客户端：维护 Cookie、CSRF 与 ProblemDetail 解析。
//
// 不依赖生产前端代码；请求与浏览器同源代理看到的 API 契约一致。

/**
 * 极简 Cookie 罐；API 只使用一个会话 Cookie，但仍按名称保存，避免硬编码顺序。
 */
export class CookieJar {
  constructor() {
    this.cookies = new Map();
  }

  /** 从响应头吸收 Set-Cookie。 */
  absorb(response) {
    const values =
      typeof response.headers.getSetCookie === "function"
        ? response.headers.getSetCookie()
        : [];
    for (const value of values) {
      const pair = value.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0) {
        this.cookies.set(
          pair.slice(0, separator).trim(),
          pair.slice(separator + 1).trim(),
        );
      }
    }
  }

  /** 生成 Cookie 请求头。 */
  header() {
    return [...this.cookies.entries()]
      .map(([name, value]) => `${name}=${value}`)
      .join("; ");
  }

  /** 清空会话。 */
  clear() {
    this.cookies.clear();
  }
}

/**
 * API 客户端。
 */
export class ApiClient {
  /**
   * @param {string} baseUrl API 根地址，例如 http://127.0.0.1:18080
   */
  constructor(baseUrl) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.jar = new CookieJar();
    this.csrfToken = null;
    this.csrfHeader = "X-CSRF-TOKEN";
    this.session = null;
  }

  /**
   * 发起请求并返回状态、头与 JSON/文本。
   *
   * @param {string} method HTTP 方法
   * @param {string} path API 路径
   * @param {object} [options] body、headers、raw
   */
  async request(method, path, options = {}) {
    const headers = { ...(options.headers ?? {}) };
    const cookie = this.jar.header();
    if (cookie) {
      headers.cookie = cookie;
    }
    if (this.csrfToken && !["GET", "HEAD", "OPTIONS"].includes(method)) {
      headers[this.csrfHeader] = this.csrfToken;
    }
    let body = options.body;
    if (
      body !== undefined &&
      !(body instanceof FormData) &&
      !(body instanceof Uint8Array)
    ) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(body);
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body,
      redirect: "manual",
    });
    this.jar.absorb(response);
    const contentType = response.headers.get("content-type") ?? "";
    const text = await response.text();
    let data = null;
    if (text) {
      if (contentType.includes("json")) {
        try {
          data = JSON.parse(text);
        } catch {
          data = text;
        }
      } else {
        data = text;
      }
    }
    return { status: response.status, headers: response.headers, data, text };
  }

  /** GET。 */
  get(path) {
    return this.request("GET", path);
  }

  /** POST JSON。 */
  post(path, body, headers = {}) {
    return this.request("POST", path, { body, headers });
  }

  /** PATCH JSON。 */
  patch(path, body) {
    return this.request("PATCH", path, { body });
  }

  /** DELETE。 */
  delete(path) {
    return this.request("DELETE", path);
  }

  /**
   * 获取匿名 CSRF 令牌并保存。
   */
  async csrf() {
    const response = await this.get("/api/v1/auth/csrf");
    if (response.status !== 200) {
      throw new Error(`csrf failed: ${response.status} ${response.text}`);
    }
    this.csrfHeader = response.data.headerName ?? "X-CSRF-TOKEN";
    this.csrfToken = response.data.token;
    return this.csrfToken;
  }

  /**
   * 登录；成功后保存会话与轮换后的 CSRF。
   */
  async login(tenantCode, username, password) {
    this.jar.clear();
    this.csrfToken = null;
    await this.csrf();
    const response = await this.post("/api/v1/auth/login", {
      tenantCode,
      username,
      password,
    });
    if (response.status !== 200) {
      throw new Error(`login failed: ${response.status} ${response.text}`);
    }
    this.csrfToken = response.data.csrfToken;
    this.session = response.data;
    return response.data;
  }

  /**
   * 上传 multipart 文件。
   */
  async upload(workspaceId, { filename, mimeType, content, conversationId }) {
    const form = new FormData();
    form.set("file", new Blob([content], { type: mimeType }), filename);
    if (conversationId) {
      form.set("conversationId", conversationId);
    }
    return this.request("POST", `/api/v1/workspaces/${workspaceId}/files`, {
      body: form,
    });
  }
}

/**
 * 轮询直到条件满足或超时。
 *
 * @param {() => Promise<any>} action 返回 truthy 表示完成
 * @param {number} timeoutMs 超时毫秒
 * @param {number} intervalMs 轮询间隔
 */
export async function waitFor(action, timeoutMs = 60_000, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await action();
    if (last) {
      return last;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(
    `waitFor timeout after ${timeoutMs}ms; last=${JSON.stringify(last)}`,
  );
}
