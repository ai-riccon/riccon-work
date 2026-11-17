#!/usr/bin/env node
// 真实栈 J2 闭环：创建 → 检索 → MCP 审批 → 生成 → 产物/引用；以及审批拒绝路径。
//
// 与浏览器用例互补：J2 的工具列表端点当前在真实 API 缺失，因此本脚本通过 OpenAPI
// 直接创建任务并驱动审批，验证 API + Worker + MCP + 假模型 + MySQL + S3 的完整链路。
// 任何主路径断言失败都会以非零退出；拒绝路径的阶段不一致记录到 observations，供评审引用。

import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient, waitFor } from "./lib/http.mjs";

const seedPath =
  process.env.REAL_SEED_OUT ??
  path.join(process.cwd(), ".riccon/evidence/TASK-0024/real-stack-seed.json");
const seed = JSON.parse(readFileSync(seedPath, "utf8"));
const topic = "2026 年中国企业协同办公市场规模与竞争格局";
const client = new ApiClient(seed.apiBase);
await client.login(
  seed.tenants.alpha,
  "alpha-member",
  "alpha-member-password-12",
);
const workspaceId = seed.workspaces.alpha.id;
const observations = {};

/**
 * 创建一个报告任务并等待审批。
 */
async function createWaitingTask(runTopic) {
  const created = await client.post(
    `/api/v1/workspaces/${workspaceId}/research-tasks`,
    { topic: runTopic, mcpToolId: seed.catalog.mcpToolId },
    { "Idempotency-Key": `j2-${Date.now()}-${Math.random()}` },
  );
  assert.equal(
    created.status,
    201,
    `create task: ${created.status} ${created.text}`,
  );
  const taskId = created.data.id;
  const waiting = await waitFor(
    async () => {
      const detail = await client.get(`/api/v1/research-tasks/${taskId}`);
      if (detail.data?.status === "WAITING_APPROVAL") {
        return detail.data;
      }
      if (["FAILED", "CANCELLED", "SUCCEEDED"].includes(detail.data?.status)) {
        throw new Error(
          `task ${taskId} left running before approval: ${detail.data?.status} ${detail.data?.failureCode}`,
        );
      }
      return null;
    },
    120_000,
    500,
  );
  return { taskId, waiting };
}

// ---------- 主路径：批准并生成报告 ----------
const { taskId, waiting } = await createWaitingTask(topic);
const retrieval = waiting.stages.find((stage) => stage.stage === "RETRIEVAL");
assert.equal(retrieval?.status, "SUCCEEDED", "retrieval stage must succeed");
assert.ok(
  (retrieval.details?.hitCount ?? 0) >= 1,
  "retrieval must hit knowledge chunks",
);
// M02-AC-07 要求审批前展示命中条目、来源与片段。真实 API 当前只返回 hitCount，
// 阶段 details.sources 被 DTO 白名单丢弃，任务级 sources 只在产物生成后才有值。
const retrievalSources = Array.isArray(retrieval.details?.sources)
  ? retrieval.details.sources
  : [];
observations.retrievalHitCount = retrieval.details?.hitCount ?? 0;
observations.retrievalSourceListInStage = retrievalSources.length;
observations.taskSourcesBeforeArtifact = Array.isArray(waiting.sources)
  ? waiting.sources.length
  : 0;
assert.ok(waiting.approvals.length >= 1, "approval must be requested");
const approval = waiting.approvals[0];
assert.equal(approval.status, "PENDING");
assert.equal(approval.toolName, "market_data");
assert.ok(
  approval.paramsPreview?.query,
  "approval preview must show tool arguments",
);
assert.ok(
  typeof approval.paramsSha256 === "string" &&
    approval.paramsSha256.length === 64,
);

const approved = await client.post(
  `/api/v1/approvals/${approval.id}/decision`,
  { decision: "APPROVE", comment: "真实栈验证：批准只读取数" },
  { "Idempotency-Key": `approve-${Date.now()}` },
);
assert.equal(
  approved.status,
  200,
  `approve: ${approved.status} ${approved.text}`,
);
assert.equal(approved.data.status, "APPROVED");

const succeeded = await waitFor(
  async () => {
    const detail = await client.get(`/api/v1/research-tasks/${taskId}`);
    if (detail.data?.status === "SUCCEEDED") {
      return detail.data;
    }
    if (["FAILED", "CANCELLED"].includes(detail.data?.status)) {
      throw new Error(
        `task ${taskId} ended ${detail.data.status}: ${detail.data.failureCode} ${detail.data.failureMessage}`,
      );
    }
    return null;
  },
  180_000,
  1000,
);

assert.deepEqual(
  succeeded.stages.map((stage) => `${stage.stage}:${stage.status}`),
  [
    "RETRIEVAL:SUCCEEDED",
    "TOOL:SUCCEEDED",
    "APPROVAL:SUCCEEDED",
    "GENERATION:SUCCEEDED",
  ],
  "all stages must succeed",
);
assert.equal(succeeded.mcpInvocations.length, 1, "one MCP invocation");
assert.equal(succeeded.mcpInvocations[0].status, "SUCCEEDED");
assert.ok(succeeded.artifact, "artifact must exist");
assert.equal(succeeded.artifact.currentVersion, 1);
assert.ok(
  succeeded.sources.length >= 2,
  "artifact must cite knowledge and MCP",
);
assert.ok(
  succeeded.sources.some((source) => source.sourceType === "KNOWLEDGE"),
);
assert.ok(succeeded.sources.some((source) => source.sourceType === "MCP"));
assert.ok(
  succeeded.sources.some(
    (source) => source.mcpInvocationId === succeeded.mcpInvocations[0].id,
  ),
  "MCP citation must reference the invocation",
);

// 产物正文与引用清单可读取。
const artifactId = succeeded.artifact.id;
const artifactDetail = await client.get(`/api/v1/artifacts/${artifactId}`);
assert.equal(
  artifactDetail.status,
  200,
  `artifact detail: ${artifactDetail.text}`,
);
assert.ok(
  artifactDetail.data.currentVersionDetail,
  "artifact must have current version detail",
);
const citations = artifactDetail.data.currentVersionDetail.citations ?? [];
assert.equal(
  citations.length,
  succeeded.sources.length,
  "artifact citations must match timeline sources",
);
const download = await client.get(
  `/api/v1/artifacts/${artifactId}/versions/1/download-url`,
);
assert.equal(download.status, 200, `artifact download url: ${download.text}`);
const contentResponse = await fetch(download.data.url);
assert.equal(
  contentResponse.status,
  200,
  "artifact content must be downloadable",
);
const markdown = await contentResponse.text();
for (const marker of [
  "# ",
  "## 摘要",
  "## 分析",
  "## 引用清单",
  "## 数据来源",
  "MCP 工具",
]) {
  assert.ok(markdown.includes(marker), `artifact must contain ${marker}`);
}

// ---------- 拒绝路径：终止且无产物 ----------
const rejectedTask = await createWaitingTask(
  "2026 年企业协同办公市场进入策略（拒绝路径）",
);
const rejectedApproval = rejectedTask.waiting.approvals[0];
const rejected = await client.post(
  `/api/v1/approvals/${rejectedApproval.id}/decision`,
  { decision: "REJECT", comment: "数据范围超出本次调研需要" },
  { "Idempotency-Key": `reject-${Date.now()}` },
);
assert.equal(
  rejected.status,
  200,
  `reject: ${rejected.status} ${rejected.text}`,
);
assert.equal(rejected.data.status, "REJECTED");
const cancelled = await waitFor(
  async () => {
    const detail = await client.get(
      `/api/v1/research-tasks/${rejectedTask.taskId}`,
    );
    return detail.data?.status === "CANCELLED" ? detail.data : null;
  },
  60_000,
  500,
);
assert.equal(
  cancelled.artifact,
  null,
  "rejected task must not produce artifact",
);
assert.equal(
  cancelled.sources.length,
  0,
  "rejected task must not expose sources",
);
const rejectedRecord = cancelled.approvals[0];
assert.equal(rejectedRecord.status, "REJECTED");
assert.ok(rejectedRecord.decidedBy, "rejected approval must record decider");
assert.ok(
  rejectedRecord.decidedAt,
  "rejected approval must record decision time",
);
assert.equal(rejectedRecord.decisionComment, "数据范围超出本次调研需要");
const staleStages = cancelled.stages.filter(
  (stage) => stage.status === "RUNNING",
);
if (staleStages.length > 0) {
  observations.rejectStagesStillRunning = staleStages.map(
    (stage) => `${stage.stage}:${stage.status}`,
  );
}

const summary = {
  status: "passed",
  generatedAt: new Date().toISOString(),
  approvedTaskId: taskId,
  approvedArtifactId: artifactId,
  citationCount: citations.length,
  mcpInvocationId: succeeded.mcpInvocations[0].id,
  firstStageDetailsSources: retrievalSources.length,
  rejectedTaskId: rejectedTask.taskId,
  observations,
};
writeFileSync(
  path.join(process.cwd(), ".riccon/evidence/TASK-0024/real-stack-j2.json"),
  `${JSON.stringify(summary, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
