# 测试 IdP（M10-V1）

`e2e/oidp/server.mjs` 是只在验证环境启动的本地 OIDC 提供方，**不是产品代码，不进入生产镜像**。

## 用途

- 提供 discovery、authorize、token、JWKS、userinfo（故意存在但必须零调用）与 end-session；
- 校验 state/nonce/PKCE(S256)、授权码一次性与 Basic 客户端认证；
- 故障注入：`deny`（access_denied）、`down`（discovery/authorize 503）、`bad_state`、
  `bad_nonce`；
- 记录 authorize/token/userinfo/end-session 次数，供“无 userinfo”与联合登出断言。

## 运行

```bash
OIDP_PORT=18995 \
OIDP_REALMS='{"alpha":{"clientId":"riccon-alpha","clientSecret":"运行时随机","subject":"subject-alpha"}}' \
node e2e/oidp/server.mjs
```

`clientSecret` 与 RSA 私钥都只在运行时生成/注入，不写入仓库（OPS-011）。

## 控制端点

| 端点                      | 说明                                 |
| ------------------------- | ------------------------------------ |
| `GET /__oidc__/health`    | 健康检查                             |
| `GET /__oidc__/stats`     | 计数器                               |
| `POST /__oidc__/reset`    | 清空授权码与计数                     |
| `POST /__oidc__/behavior` | `{realm, mode, subject?}`；mode 见上 |

控制端点不属于产品契约，只由 `scripts/real-stack` 使用。
