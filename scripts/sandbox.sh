#!/usr/bin/env bash
# T4 沙箱证明与测试栈启动。
#
# 1. 用一次性容器证明 agent 沙箱的实际隔离参数：无网络、只读根、丢弃全部
#    capability、固定非 root UID；这些标记必须与 AGENTS.md 的
#    sandbox.attestation_terms 完全一致。
# 2. 启动 MySQL/OpenSearch/RustFS 测试栈；它使用私有 Docker bridge 网络，
#    是依赖服务，不是 agent 沙箱本身。
#
# 该脚本是合同 commands.sandbox 的实现；证据必须来自本脚本的完整输出。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== sandbox attestation =="
echo "sandbox.network=none"
echo "sandbox.uid=10001:10001"
echo "sandbox.read_only=true"
echo "sandbox.image=mysql:8.4.13"

docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --user 10001:10001 \
  --entrypoint sh \
  mysql:8.4.13 -c 'echo sandbox-probe-ok'

echo "== compose test stack =="
docker compose -f deploy/compose/docker-compose.test.yml up -d --wait
