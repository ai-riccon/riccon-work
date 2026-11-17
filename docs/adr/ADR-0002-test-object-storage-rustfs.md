# ADR-0002：测试栈对象存储使用 RustFS 替代已停止公开发布的 MinIO 镜像

Status: accepted
Date: 2026-11-17

## Context

合同 `stack` 的存储集成写的是“S3 兼容（开发和小规模用 MinIO；一体机分发改用 RustFS/SeaweedFS）”。M01 需要 `deploy/compose/docker-compose.test.yml` 提供本地与 CI 的 S3 兼容服务。实际核查发现 MinIO 官方 Docker 镜像已无法公开获取：

- Docker Hub `minio/minio` 仓库返回 `object not found`；
- `quay.io/minio/minio` 返回 401，无法匿名拉取；
- `minio/minio:RELEASE.2025-04-22T22-12-26Z` 与 `minio/minio:latest` 均无法解析。

同时 `rustfs/rustfs:1.0.6` 与 `chrislusf/seaweedfs` 可正常拉取；合同已把 RustFS/SeaweedFS 列为一体机分发的候选。

## Decision

测试栈使用 `rustfs/rustfs:1.0.6` 作为 S3 兼容对象存储，服务名为 `object-storage`，端口映射到宿主机 `19000/19001`。应用代码只依赖 S3 协议，不感知具体实现；生产与一体机的对象存储选型仍由 M09/ops 决定。

## Consequences

- 正面：测试栈可重复启动，健康检查通过；不引入未维护的第三方 MinIO 镜像；与合同已认可的 RustFS 方向一致。
- 负面：开发环境与合同字面上的“MinIO”不一致；RustFS 版本较新（1.0.x），需要关注兼容性。
- 合同影响：建议把合同 `integrations.storage` 的开发侧实现改为“S3 兼容（测试栈用 RustFS；生产由 ops 选型）”。该修改需要 Lead 走合同变更，不在本 ADR 中直接改 `AGENTS.md`。

## Alternatives

- **保留 `minio/minio` 镜像引用**：无法拉取，CI 与本地 sandbox 直接失败。
- **`bitnamilegacy/minio`**：仍是 MinIO 二进制，但属于已归档的 legacy 命名空间，安全更新不可预期，不适合 T4 基线。
- **SeaweedFS**：成熟且合同允许，但 S3 网关需要额外启动参数，配置复杂度高于 RustFS；留作生产候选。
- **自建 MinIO 镜像**：需要维护构建链与供应链证明，超出 M01 范围。

## Evidence

- `docker manifest inspect minio/minio:latest` → 失败；Docker Hub API `minio/minio` → `object not found`
- `docker manifest inspect rustfs/rustfs:latest` → 成功
- `docker compose -f deploy/compose/docker-compose.test.yml up -d --wait` → 三个服务全部 Healthy
- `curl http://127.0.0.1:19000/health` → `{"service":"rustfs-endpoint","status":"ok","version":"1.0.6"}`
- `deploy/compose/docker-compose.test.yml`
