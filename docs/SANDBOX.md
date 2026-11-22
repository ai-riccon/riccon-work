# 沙箱

T4 或处理敏感数据的项目必须在一个隔离运行时中执行构建、测试、迁移演练和预览验证。本文描述项目实际使用的沙箱，不是通用安全承诺。

## 挂载

- 只挂载项目目录；默认只读。
- 需要写入时使用临时目录或一次性卷。
- 不挂载宿主的 `$HOME`、云凭据、SSH key、浏览器配置或生产数据。

## 网络

- **Agent/沙箱探针默认 `network: none`**，不访问外部网络；需要依赖安装时使用一次性网络，安装后立即断开。
- **Compose 测试栈（MySQL/OpenSearch/RustFS）是依赖服务，不是 agent 沙箱**：只使用私有 Docker bridge 网络，端口仅绑定 `127.0.0.1`，不暴露到宿主机以外的网络。
- 合同 `sandbox.network: none` 时不使用 `sandbox.allowlist`；若未来沙箱需要访问模型或 MCP，必须改为 `allowlist` 并列出真实端点，走用户决策，不在本阶段静默放开。
- 模型/MCP 调用发生在 worker 进程，不在沙箱容器内；外部模型端点只处理合成数据，PII 必须走客户内网网关。
- 不访问生产数据库、生产 API 或内部管理面。
- 需要外部服务时使用本地 mock 或一次性测试账号。

## 数据库

- 测试栈 MySQL 8.4 默认开启 binlog；V6 的审计触发器由非 SUPER 的 `riccon` 用户创建，因此 compose 固定 `--log-bin-trust-function-creators=1`。该放宽只属于一次性测试栈，不是生产基线；生产迁移账号权限由 M09 决定，或由 DBA 预建触发器。
- 迁移演练使用全新卷，结束后 `down -v` 销毁；不要在演练卷上继续启动 API 做 Flyway 迁移（见 `docs/RUNBOOK.md`）。

## 密钥

- 沙箱不注入生产密钥。
- 测试密钥通过环境变量注入，运行结束即销毁。
- 不把密钥写入镜像、日志、证据或 `.riccon/`。

## 清理

- 容器/VM 使用一次性名称，退出即删除。
- 临时卷、数据库副本和测试数据在演练后删除。
- 失败时保留日志和证据，不保留密钥和 PII。

## 证明

- `commands.sandbox` = `bash scripts/sandbox.sh`：脚本先用一次性容器 `docker run --rm --network none --read-only --cap-drop ALL --user 10001:10001` 打印 attestation 标记，再启动 Compose 测试栈。
- `sandbox.attestation_terms` 必须与脚本日志中的标记一致：`sandbox.network=none`、`sandbox.uid=10001:10001`、`sandbox.read_only=true`、`sandbox.image=mysql:8.4.13`。
- `sandbox.evidence` 必须指向在该脚本上执行、`dirty=false`、exit 0 的 passing EV；release evidence 引用同一 EV。
- 证据缺失、命令不匹配、非 passing 或缺少任一 attestation 标记时，`sandbox check` 失败，不得 done/release。

## 限制

- `sandbox.limits` 的 CPU（`cpus`）、内存、磁盘和墙钟上限。
- 禁止宿主机 Docker socket、特权模式和宿主网络。
- 记录沙箱命令和结果到证据；没有通过证据不 done。
