#!/usr/bin/env node
// M10 测试 IdP：本地 OIDC Authorization Code + PKCE(S256) + nonce 提供方。
//
// 定位：只由验证脚本（real-stack / E2E）在测试环境启动，**不是**产品代码，不进入生产镜像。
// 它提供 discovery、authorize、token、JWKS、userinfo（故意存在但必须零调用）与 end-session；
// 支持故障注入（access_denied / idp_down / bad_state / bad_nonce）与 subject 切换，用于验证
// 登录失败清理、无 userinfo、联合登出与账号绑定端到端。
//
// 安全约束（OPS-011）：
// - RSA 密钥每次启动时运行时生成，不写入仓库；
// - clientSecret 只从 OIDP_REALMS 环境变量读取，测试脚本运行时随机生成，不落盘；
// - 输出日志不打印 token、nonce 原文或 clientSecret。
//
// 运行：OIDP_PORT=18995 OIDP_REALMS='{"alpha":{...}}' node e2e/oidp/server.mjs

import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import http from 'node:http';

/** 监听端口；real-stack 通过环境变量固定，保证 API issuer 配置可预先构造。 */
const PORT = Number(process.env.OIDP_PORT ?? 18995);
const HOST = process.env.OIDP_HOST ?? '127.0.0.1';

/** realm 配置：{ alpha: { clientId, clientSecret, subject, email } }。 */
const REALMS = JSON.parse(process.env.OIDP_REALMS ?? '{}');

/** 运行时生成的 RS256 密钥（不落盘）。 */
const { publicKey, privateKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
});
const KEY_ID = `oidp-${randomUUID().slice(0, 8)}`;
const PUBLIC_JWK = {
  ...publicKey.export({ format: 'jwk' }),
  kid: KEY_ID,
  use: 'sig',
  alg: 'RS256',
};

/** 各 realm 行为：login | deny | down | bad_state | bad_nonce。 */
const behaviors = new Map(
  Object.keys(REALMS).map((realm) => [realm, { mode: 'login', subject: null }]),
);

/** 一次性授权码：code -> 记录。 */
const codes = new Map();

/** 计数器。 */
const stats = {
  discovery: 0,
  authorize: 0,
  token: 0,
  userinfo: 0,
  endSession: 0,
};

/** 最近一次授权请求中的 nonce 是否来自测试（只保留是否成功，不保存原文）。 */
let lastNonceVerified = null;

/**
 * 输出 JSON。
 *
 * @param {http.ServerResponse} res 响应
 * @param {number} status 状态码
 * @param {object} body 响应体
 */
function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(text);
}

/**
 * 读取请求体。
 *
 * @param {http.IncomingMessage} req 请求
 * @returns {Promise<string>} 文本
 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/**
 * 解析 RFC 3986 查询串。
 *
 * @param {URLSearchParams} params 参数
 * @returns {Record<string, string>} 对象
 */
function paramsObject(params) {
  return Object.fromEntries(params.entries());
}

/** base64url 编码。 */
function b64url(value) {
  return Buffer.from(value).toString('base64url');
}

/**
 * 签发 RS256 ID Token。
 *
 * @param {object} input realm/clientId/subject/nonce
 * @returns {string} 紧凑 JWT
 */
function signIdToken({ realm, clientId, subject, nonce }) {
  const issuer = `http://${HOST}:${PORT}/realms/${realm}`;
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', kid: KEY_ID, typ: 'JWT' };
  const claims = {
    iss: issuer,
    aud: clientId,
    sub: subject,
    iat: now,
    exp: now + 300,
    nonce,
    // 故意带邮箱：产品不得按邮箱绑定（ADR-0019 §3）。
    email: REALMS[realm]?.email ?? 'oidc-shared@example.test',
  };
  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const signature = sign('RSA-SHA256', Buffer.from(signingInput), privateKey);
  return `${signingInput}.${b64url(signature)}`;
}

/**
 * PKCE S256 校验。
 *
 * @param {string} challenge 授权请求中的 challenge
 * @param {string} verifier token 请求中的 verifier
 * @returns {boolean} 是否匹配
 */
function pkceMatches(challenge, verifier) {
  if (!challenge || !verifier) return false;
  return createHash('sha256').update(verifier).digest('base64url') === challenge;
}

/**
 * 解析 Basic 客户端认证。
 *
 * @param {string | undefined} header Authorization 头
 * @returns {{ clientId: string, clientSecret: string } | null} 客户端
 */
function basicAuth(header) {
  if (!header?.startsWith('Basic ')) return null;
  const decoded = Buffer.from(header.slice('Basic '.length), 'base64').toString('utf8');
  const index = decoded.indexOf(':');
  if (index <= 0) return null;
  return { clientId: decoded.slice(0, index), clientSecret: decoded.slice(index + 1) };
}

/**
 * discovery 文档。
 *
 * @param {string} realm realm
 * @returns {object} 文档
 */
function discoveryDocument(realm) {
  const issuer = `http://${HOST}:${PORT}/realms/${realm}`;
  return {
    issuer,
    authorization_endpoint: `${issuer}/authorize`,
    token_endpoint: `${issuer}/token`,
    jwks_uri: `${issuer}/jwks`,
    // 故意暴露 userinfo：产品实现必须忽略并保持零调用（ADR-0019 F-011）。
    userinfo_endpoint: `${issuer}/userinfo`,
    end_session_endpoint: `${issuer}/end-session`,
    response_types_supported: ['code'],
    subject_types_supported: ['public'],
    id_token_signing_alg_values_supported: ['RS256'],
    code_challenge_methods_supported: ['S256'],
  };
}

/**
 * 主请求处理。
 *
 * @param {http.IncomingMessage} req 请求
 * @param {http.ServerResponse} res 响应
 */
async function handle(req, res) {
  const url = new URL(req.url ?? '/', `http://${HOST}:${PORT}`);
  const path = url.pathname;

  if (path === '/__oidc__/health') {
    json(res, 200, { status: 'UP', realms: Object.keys(REALMS) });
    return;
  }

  if (path === '/__oidc__/stats' && req.method === 'GET') {
    json(res, 200, { ...stats, nonceVerified: lastNonceVerified, keyId: KEY_ID });
    return;
  }

  if (path === '/__oidc__/last-nonce-verified' && req.method === 'GET') {
    json(res, 200, { verified: lastNonceVerified });
    return;
  }

  if (path === '/__oidc__/reset' && req.method === 'POST') {
    codes.clear();
    lastNonceVerified = null;
    for (const key of Object.keys(stats)) stats[key] = 0;
    for (const [realm] of behaviors) behaviors.set(realm, { mode: 'login', subject: null });
    json(res, 200, { reset: true });
    return;
  }

  if (path === '/__oidc__/behavior' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)) || '{}');
    const realm = String(body.realm ?? '');
    if (!behaviors.has(realm)) {
      json(res, 404, { error: 'unknown_realm' });
      return;
    }
    if (body.mode && !['login', 'deny', 'down', 'bad_state', 'bad_nonce'].includes(body.mode)) {
      json(res, 400, { error: 'unknown_mode' });
      return;
    }
    behaviors.set(realm, {
      mode: body.mode ?? 'login',
      subject: body.subject ?? null,
    });
    json(res, 200, { realm, mode: behaviors.get(realm).mode });
    return;
  }

  const realmMatch = /^\/realms\/([^/]+)(\/.*)$/.exec(path);
  if (!realmMatch) {
    json(res, 404, { error: 'not_found' });
    return;
  }
  const realm = realmMatch[1];
  const suffix = realmMatch[2];
  const config = REALMS[realm];
  if (!config) {
    json(res, 404, { error: 'unknown_realm' });
    return;
  }
  const behavior = behaviors.get(realm);

  if (suffix === '/.well-known/openid-configuration') {
    stats.discovery += 1;
    if (behavior.mode === 'down') {
      json(res, 503, { error: 'temporarily_unavailable' });
      return;
    }
    json(res, 200, discoveryDocument(realm));
    return;
  }

  if (suffix === '/jwks') {
    json(res, 200, { keys: [PUBLIC_JWK] });
    return;
  }

  if (suffix === '/userinfo') {
    stats.userinfo += 1;
    json(res, 200, { sub: config.subject ?? 'unexpected-userinfo' });
    return;
  }

  if (suffix === '/end-session') {
    stats.endSession += 1;
    const target = url.searchParams.get('post_logout_redirect_uri');
    if (target) {
      const redirect = new URL(target);
      const state = url.searchParams.get('state');
      if (state) redirect.searchParams.set('state', state);
      res.writeHead(302, { location: redirect.toString(), 'cache-control': 'no-store' });
      res.end();
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end('<html><body>已结束测试 IdP 会话</body></html>');
    return;
  }

  if (suffix === '/authorize') {
    stats.authorize += 1;
    const params = paramsObject(url.searchParams);
    if (behavior.mode === 'down') {
      json(res, 503, { error: 'temporarily_unavailable' });
      return;
    }
    const redirectUri = params.redirect_uri;
    if (
      params.response_type !== 'code' ||
      params.client_id !== config.clientId ||
      !redirectUri ||
      !params.state ||
      !params.nonce ||
      params.code_challenge_method !== 'S256' ||
      !params.code_challenge
    ) {
      json(res, 400, { error: 'invalid_request' });
      return;
    }
    if (behavior.mode === 'deny') {
      const target = new URL(redirectUri);
      target.searchParams.set('error', 'access_denied');
      target.searchParams.set('state', params.state);
      res.writeHead(302, { location: target.toString(), 'cache-control': 'no-store' });
      res.end();
      return;
    }
    if (behavior.mode === 'bad_state') {
      const target = new URL(redirectUri);
      target.searchParams.set('code', `bad-state-${randomUUID()}`);
      target.searchParams.set('state', `${params.state}-tampered`);
      res.writeHead(302, { location: target.toString(), 'cache-control': 'no-store' });
      res.end();
      return;
    }
    const code = `code-${randomUUID()}`;
    codes.set(code, {
      realm,
      clientId: config.clientId,
      redirectUri,
      codeChallenge: params.code_challenge,
      nonce: behavior.mode === 'bad_nonce' ? `${params.nonce}-wrong` : params.nonce,
      subject: behavior.subject ?? config.subject ?? `subject-${realm}`,
    });
    const target = new URL(redirectUri);
    target.searchParams.set('code', code);
    target.searchParams.set('state', params.state);
    res.writeHead(302, { location: target.toString(), 'cache-control': 'no-store' });
    res.end();
    return;
  }

  if (suffix === '/token' && req.method === 'POST') {
    stats.token += 1;
    const auth = basicAuth(req.headers.authorization);
    const form = new URLSearchParams(await readBody(req));
    const entry = codes.get(form.get('code') ?? '');
    codes.delete(form.get('code') ?? '');
    if (
      !entry ||
      !auth ||
      auth.clientId !== config.clientId ||
      auth.clientSecret !== config.clientSecret ||
      entry.realm !== realm ||
      entry.clientId !== config.clientId ||
      form.get('grant_type') !== 'authorization_code' ||
      form.get('redirect_uri') !== entry.redirectUri ||
      !pkceMatches(entry.codeChallenge, form.get('code_verifier'))
    ) {
      json(res, 400, { error: 'invalid_grant' });
      return;
    }
    lastNonceVerified = true;
    json(res, 200, {
      access_token: `oidp-access-${randomUUID()}`,
      token_type: 'Bearer',
      expires_in: 300,
      id_token: signIdToken({
        realm,
        clientId: config.clientId,
        subject: entry.subject,
        nonce: entry.nonce,
      }),
    });
    return;
  }

  json(res, 404, { error: 'not_found' });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    console.error('[oidp] request failed:', error?.message ?? 'unknown');
    if (!res.headersSent) json(res, 500, { error: 'server_error' });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[oidp] listening on http://${HOST}:${PORT} realms=${Object.keys(REALMS).join(',')}`);
});
