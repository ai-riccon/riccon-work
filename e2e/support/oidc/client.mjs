// OIDC 验证辅助：PKCE、授权码交换与 ID Token 校验。
//
// 只被验证脚本使用（`scripts/real-stack/m10-checks.mjs`、`e2e/oidp` 测试），
// 不进入产品代码；不持久化 token，也不读取 userinfo。

import { createHash, createPublicKey, createVerify, randomBytes } from 'node:crypto';

/**
 * 生成 PKCE S256 verifier/challenge。
 *
 * @returns {{ verifier: string, challenge: string }} verifier 与 challenge
 */
export function createPkce() {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

/**
 * 读取 discovery 文档。
 *
 * @param {string} issuer issuer
 * @returns {Promise<object>} 文档
 */
export async function discover(issuer) {
  const response = await fetch(`${issuer}/.well-known/openid-configuration`);
  if (!response.ok) {
    throw new Error(`discovery failed: ${response.status}`);
  }
  return response.json();
}

/**
 * 构造授权 URL。
 *
 * @param {object} input discovery/客户端/回调/state/nonce/PKCE
 * @returns {string} 授权 URL
 */
export function authorizeUrl({
  authorizationEndpoint,
  clientId,
  redirectUri,
  state,
  nonce,
  codeChallenge,
}) {
  const url = new URL(authorizationEndpoint);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('redirect_uri', redirectUri);
  url.searchParams.set('scope', 'openid profile email');
  url.searchParams.set('state', state);
  url.searchParams.set('nonce', nonce);
  url.searchParams.set('code_challenge', codeChallenge);
  url.searchParams.set('code_challenge_method', 'S256');
  return url.toString();
}

/**
 * 交换授权码。
 *
 * @param {object} input tokenEndpoint/clientId/clientSecret/code/verifier/redirectUri
 * @returns {Promise<object>} token 响应
 */
export async function exchangeCode({
  tokenEndpoint,
  clientId,
  clientSecret,
  code,
  codeVerifier,
  redirectUri,
}) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    code_verifier: codeVerifier,
  });
  const response = await fetch(tokenEndpoint, {
    method: 'POST',
    headers: {
      authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}`,
      'content-type': 'application/x-www-form-urlencoded',
    },
    body,
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`token exchange failed: ${response.status} ${text}`);
  }
  return JSON.parse(text);
}

/**
 * 从 JWKS 取出 RSA 公钥。
 *
 * @param {object} jwk JWK
 * @returns {import('node:crypto').KeyObject} 公钥
 */
function publicKeyFromJwk(jwk) {
  return createPublicKey({ key: jwk, format: 'jwk' });
}

/**
 * 校验并解析 ID Token（签名、iss、aud、exp、nonce）。
 *
 * @param {object} input idToken/jwks/issuer/clientId/nonce
 * @returns {object} claims
 */
export function verifyIdToken({ idToken, jwks, issuer, clientId, nonce }) {
  const parts = String(idToken).split('.');
  if (parts.length !== 3) {
    throw new Error('id_token is not a JWT');
  }
  const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
  const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  const jwk = jwks.keys.find((key) => key.kid === header.kid);
  if (!jwk) {
    throw new Error('id_token kid not found in JWKS');
  }
  const verifier = createVerify('RSA-SHA256');
  verifier.update(`${parts[0]}.${parts[1]}`);
  if (!verifier.verify(publicKeyFromJwk(jwk), Buffer.from(parts[2], 'base64url'))) {
    throw new Error('id_token signature invalid');
  }
  if (claims.iss !== issuer) {
    throw new Error(`id_token iss mismatch: ${claims.iss}`);
  }
  if (claims.aud !== clientId) {
    throw new Error(`id_token aud mismatch: ${claims.aud}`);
  }
  if (typeof claims.exp !== 'number' || claims.exp * 1000 < Date.now()) {
    throw new Error('id_token expired');
  }
  if (claims.nonce !== nonce) {
    throw new Error('id_token nonce mismatch');
  }
  return claims;
}
