#!/usr/bin/env node
// 真实栈 J2 闭环：创建 → 检索 → MCP 审批 → 生成 → 产物/引用；以及审批拒绝路径。
//
// 与浏览器用例互补：本脚本通过 OpenAPI 直接创建任务并驱动审批，验证 API + Worker + MCP +
// 假模型 + MySQL + S3 的完整链路，并断言检索来源片段、审批时间线与运行详情。
// 任何主路径断言失败都会以非零退出。

import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient, waitFor } from "./lib/http.mjs";

const seedPath =
  process.env.REAL_SEED_OUT ??
  path.join(process.cwd(), ".riccon/evidence/TASK-0024/real-stack-seed.json");
const evidenceDir = path.dirname(seedPath);
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
 * 断言检索来源可直接展示：标题与片段非空，片段不是标题或 UUID 的回声，且不超过 Worker 的 200 字符上限。
 *
 * @param sources 任务级或阶段级来源列表
 * @param label 断言场景标签
 */
function assertDisplayableSources(sources, label) {
  assert.ok(
    Array.isArray(sources) && sources.length >= 1,
    `${label}: must expose at least one source`,
  );
  for (const [index, source] of sources.entries()) {
    assert.ok(
      typeof source.title === "string" && source.title.trim().length > 0,
      `${label}: source[${index}] title must not be empty`,
    );
    assert.ok(
      typeof source.snippet === "string" && source.snippet.trim().length > 0,
      `${label}: source[${index}] snippet must not be empty`,
    );
    assert.ok(
      source.snippet.length <= 200,
      `${label}: source[${index}] snippet ${source.snippet.length} exceeds 200 chars`,
    );
    assert.notEqual(
      source.snippet.trim(),
      source.title.trim(),
      `${label}: source[${index}] snippet must not echo the title`,
    );
    assert.ok(
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        source.snippet.trim(),
      ),
      `${label}: source[${index}] snippet must not be a bare UUID`,
    );
  }
}

/**
 * 断言审批阶段详情含决定、审批人与审批时间（M02-AC-09 时间线）。
 *
 * @param detail 任务详情
 * @param expectedDecision APPROVE 或 REJECT
 * @param label 断言场景标签
 */
function assertApprovalTimeline(detail, expectedDecision, label) {
  for (const stageName of ["APPROVAL", "TOOL"]) {
    const stage = detail.stages.find((item) => item.stage === stageName);
    assert.ok(stage, `${label}: ${stageName} stage must exist`);
    assert.notEqual(
      stage.status,
      "RUNNING",
      `${label}: ${stageName} must not stay RUNNING`,
    );
    assert.equal(
      stage.details?.approvalDecision,
      expectedDecision,
      `${label}: ${stageName}.details.approvalDecision`,
    );
    assert.ok(
      stage.details?.approvalDecidedBy,
      `${label}: ${stageName}.details.approvalDecidedBy must be present`,
    );
    assert.ok(
      stage.details?.approvalDecidedAt,
      `${label}: ${stageName}.details.approvalDecidedAt must be present`,
    );
  }
}

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
// M02-AC-07 要求审批前展示命中条目、来源与片段。阶段 details.sources 必须映射到任务级 sources，
// 且片段非空、非标题回声；这是 TASK-0027 修复的直接验收点。
const retrievalSources = Array.isArray(retrieval.details?.sources)
  ? retrieval.details.sources
  : [];
assertDisplayableSources(
  retrievalSources,
  "stage details.sources before approval",
);
assertDisplayableSources(waiting.sources, "task sources before artifact");
assert.ok(
  waiting.sources.some(
    (source) =>
      source.sourceType === "KNOWLEDGE" &&
      source.locatorType &&
      (source.paragraphNo >= 1 || source.pageNo >= 1 || source.sheetName),
  ),
  "task sources before approval must include a locatable KNOWLEDGE citation",
);
observations.retrievalHitCount = retrieval.details?.hitCount ?? 0;
observations.retrievalSourceListInStage = retrievalSources.length;
observations.taskSourcesBeforeArtifact = Array.isArray(waiting.sources)
  ? waiting.sources.length
  : 0;
observations.firstSnippetLength = retrievalSources[0]?.snippet?.length ?? 0;
observations.firstSnippetPreview =
  retrievalSources[0]?.snippet?.slice(0, 80) ?? "";
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
assertApprovalTimeline(succeeded, "APPROVE", "approved task");
assert.ok(succeeded.currentRunId, "approved task must expose currentRunId");
const approvedRun = await client.get(`/api/v1/runs/${succeeded.currentRunId}`);
assert.equal(
  approvedRun.status,
  200,
  `run detail: ${approvedRun.status} ${approvedRun.text}`,
);
assert.equal(approvedRun.data?.kind, "RESEARCH_REPORT");
assert.ok(
  Array.isArray(approvedRun.data?.modelInvocations) &&
    approvedRun.data.modelInvocations.length >= 1,
  "J2 run detail must expose model invocations",
);
assert.ok(
  approvedRun.data.modelInvocations.some(
    (invocation) =>
      invocation.status === "SUCCEEDED" &&
      typeof invocation.modelKey === "string" &&
      invocation.modelKey.length > 0,
  ),
  "J2 run detail must show a succeeded model invocation with modelKey",
);
observations.j2RunModelInvocations = approvedRun.data.modelInvocations.length;
observations.j2RunStages = approvedRun.data.stages.map(
  (stage) => `${stage.stage}:${stage.status}`,
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
    if (detail.data?.status !== "CANCELLED") {
      return null;
    }
    // 拒绝由 API 先置终态，Worker 取消事件随后补齐阶段终态与审批决定；必须等收尾完成再断言。
    const stages = detail.data.stages ?? [];
    const settled =
      stages.length > 0 &&
      !stages.some((stage) => stage.status === "RUNNING") &&
      ["TOOL", "APPROVAL"].every((name) => {
        const stage = stages.find((item) => item.stage === name);
        return (
          stage?.details?.approvalDecision === "REJECT" &&
          stage?.details?.approvalDecidedBy &&
          stage?.details?.approvalDecidedAt
        );
      });
    return settled ? detail.data : null;
  },
  60_000,
  500,
);
assert.equal(
  cancelled.artifact,
  null,
  "rejected task must not produce artifact",
);
// 检索来源应在审批前写入并在终止后保留；M02-AC-07 不再允许“拒绝后来源为 0”的旧行为。
assert.ok(
  cancelled.sources.length >= 1,
  "rejected task should keep retrieval sources for audit",
);
assert.ok(
  cancelled.sources.every((source) => source.sourceType !== "MCP"),
  "rejected task must not cite MCP results",
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
assert.deepEqual(
  staleStages,
  [],
  "rejected task must not leave stages RUNNING",
);
assertApprovalTimeline(cancelled, "REJECT", "rejected task");
observations.rejectStageStatuses = cancelled.stages.map(
  (stage) => `${stage.stage}:${stage.status}`,
);
observations.rejectToolInvocationCount = cancelled.mcpInvocations.length;

const summary = {
  status: "passed",
  generatedAt: new Date().toISOString(),
  approvedTaskId: taskId,
  approvedRunId: succeeded.currentRunId,
  approvedArtifactId: artifactId,
  citationCount: citations.length,
  mcpInvocationId: succeeded.mcpInvocations[0].id,
  firstStageDetailsSources: retrievalSources.length,
  rejectedTaskId: rejectedTask.taskId,
  observations,
};
writeFileSync(
  path.join(evidenceDir, "real-stack-j2.json"),
  `${JSON.stringify(summary, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
