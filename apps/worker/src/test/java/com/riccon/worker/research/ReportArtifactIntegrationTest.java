package com.riccon.worker.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.agentscope.McpMockServer;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.research.domain.ArtifactCitationRecord;
import com.riccon.worker.research.domain.ArtifactRecord;
import com.riccon.worker.research.domain.ArtifactVersionRecord;
import com.riccon.worker.research.domain.ReportCitation;
import com.riccon.worker.research.domain.ReportDocument;
import com.riccon.worker.research.domain.ResearchTaskRecord;
import com.riccon.worker.research.domain.RunStageDetails;
import com.riccon.worker.research.repository.ArtifactRepository;
import com.riccon.worker.research.repository.ResearchRepository;
import com.riccon.worker.research.service.ReportArtifactService.SavedArtifact;
import com.riccon.worker.research.service.ReportArtifactWriter;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestCredentials;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestMcpCommands;
import com.riccon.worker.support.TestModelStub;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** J2 报告编排：审批、工具调用、Markdown 产物、引用清单、重试与幂等。 */
class ReportArtifactIntegrationTest extends WorkerIntegrationTestBase {

    /** Outbox 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** JSON 解析。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 产物仓储。 */
    @Autowired
    private ArtifactRepository artifactRepository;

    /** 报告仓储。 */
    @Autowired
    private ResearchRepository researchRepository;

    /** 产物写入编排。 */
    @Autowired
    private ReportArtifactWriter artifactWriter;

    /** 模型桩。 */
    private TestModelStub stub;

    /** 启动模型桩。 */
    @BeforeEach
    void setUp() {
        stub = new TestModelStub();
    }

    /** 关闭模型桩。 */
    @AfterEach
    void tearDown() {
        stub.close();
    }

    /** 审批通过：执行工具、生成报告、保存产物与引用，并写入阶段摘要与审计。 */
    @Test
    void approved_persistsArtifactVersionAndTraceableCitations() {
        Fixture fixture = fixture("tenant-a", true);

        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "RETRIEVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "GENERATION")).isEqualTo("SUCCEEDED");
        assertThat(auditCount("artifact.saved")).isEqualTo(1);

        ArtifactRecord artifact = tenantCall(
                "tenant-a",
                () -> artifactRepository
                        .findByTask(TenantId.of("tenant-a"), fixture.taskId())
                        .orElseThrow());
        assertThat(artifact.currentVersion()).isEqualTo(1);
        ArtifactVersionRecord version = tenantCall(
                "tenant-a",
                () -> artifactRepository
                        .findVersion(TenantId.of("tenant-a"), artifact.id(), 1)
                        .orElseThrow());
        assertThat(version.contentType()).isEqualTo("text/markdown");
        assertThat(version.storageKey()).startsWith("tenants/tenant-a/artifact/");
        assertThat(version.sha256()).hasSize(64);
        assertThat(version.sizeBytes()).isPositive();

        List<ArtifactCitationRecord> citations =
                tenantCall("tenant-a", () -> artifactRepository.findCitations(TenantId.of("tenant-a"), version.id()));
        assertThat(citations).hasSize(2);
        ArtifactCitationRecord knowledge = citations.get(0);
        assertThat(knowledge.ordinal()).isEqualTo(1);
        assertThat(knowledge.sourceType()).isEqualTo("KNOWLEDGE");
        assertThat(knowledge.knowledgeDocumentId()).isEqualTo(fixture.documentId());
        assertThat(knowledge.titleSnapshot()).isEqualTo("市场资料");
        assertThat(knowledge.paragraphNo()).isEqualTo(1);
        ArtifactCitationRecord mcp = citations.get(1);
        assertThat(mcp.ordinal()).isEqualTo(2);
        assertThat(mcp.sourceType()).isEqualTo("MCP");
        assertThat(mcp.mcpInvocationId()).isEqualTo(UUID.fromString(mcpInvocationId(fixture.runId())));
        assertThat(mcp.mcpInvocationId()).isNotNull();

        String body = readObject(version.storageKey());
        assertThat(body)
                .contains("# 市场调研 · 市场调研报告")
                .contains("## 摘要")
                .contains("## 分析")
                .contains("## 引用清单")
                .contains("## 数据来源")
                .contains("市场资料（第 1 段）")
                .contains("MCP 工具 `echo`")
                .contains("`fake-model`");
        assertThat(version.sha256()).isEqualTo(sha256(body));
        assertThat(version.sizeBytes()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);

        JsonNode retrieval = objectMapper.readTree(stageDetails(fixture.runId(), "RETRIEVAL"));
        assertThat(retrieval.path("query").asText()).isEqualTo("市场调研");
        assertThat(retrieval.path("querySha256").asText()).hasSize(64);
        assertThat(retrieval.path("hitCount").asInt()).isEqualTo(1);
        assertThat(retrieval.path("sources")).hasSize(1);
        JsonNode retrievalSource = retrieval.path("sources").get(0);
        assertThat(retrievalSource.path("knowledgeDocumentId").asText())
                .isEqualTo(fixture.documentId().toString());
        assertThat(retrievalSource.path("title").asText()).isEqualTo("市场资料");
        assertThat(retrievalSource.path("snippet").asText()).isEqualTo("市场保持增长。");
        assertThat(retrievalSource.path("snippet").asText())
                .isNotEqualTo(retrievalSource.path("title").asText());
        assertThat(retrievalSource.path("chunkId").asText()).isNotBlank();
        JsonNode tool = objectMapper.readTree(stageDetails(fixture.runId(), "TOOL"));
        assertThat(tool.path("paramsPreview").path("text").asText()).isEqualTo("market-data");
        assertThat(tool.path("resultSummary").asText()).startsWith("sha256=");
        assertThat(tool.path("invocationId").asText()).isEqualTo(mcpInvocationId(fixture.runId()));
        JsonNode approval = objectMapper.readTree(stageDetails(fixture.runId(), "APPROVAL"));
        assertThat(approval.path("approvalId").asText()).isEqualTo(approvalId.toString());
        assertThat(approval.path("approvalDecision").asText()).isEqualTo("APPROVE");
        assertThat(approval.path("approvalDecidedBy").asText())
                .isEqualTo(fixture.userId().toString());
        assertThat(approval.path("approvalDecidedAt").asText()).isNotBlank();
        JsonNode generation = objectMapper.readTree(stageDetails(fixture.runId(), "GENERATION"));
        assertThat(generation.path("generatedSha256").asText()).hasSize(64);
        assertThat(generation.path("generatedChars").asInt()).isPositive();

        String auditDetails = auditDetails("artifact.saved");
        assertThat(auditDetails)
                .contains(artifact.id().toString())
                .contains(fixture.runId().toString())
                .contains("\"version\": 1");
    }

    /** 审批拒绝：终止运行与任务、记录决定与审批人，不产生任何产物。 */
    @Test
    void rejected_terminatesWithoutArtifact() {
        Fixture fixture = fixture("tenant-a", true);

        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        reject(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(resumeEvent)).isEqualTo("DONE");
        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("CANCELLED");
        assertThat(taskFailureCode(fixture.taskId())).isEqualTo("APPROVAL_REJECTED");
        assertThat(artifactCount()).isZero();
        assertThat(auditCount("artifact.saved")).isZero();
        assertThat(mcpInvocationCount(fixture.runId())).isZero();
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SKIPPED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("SKIPPED");
        JsonNode approval = objectMapper.readTree(stageDetails(fixture.runId(), "APPROVAL"));
        assertThat(approval.path("approvalId").asText()).isEqualTo(approvalId.toString());
        assertThat(approval.path("approvalDecision").asText()).isEqualTo("REJECT");
        assertThat(approval.path("approvalDecidedBy").asText())
                .isEqualTo(fixture.userId().toString());
        assertThat(approval.path("approvalDecidedAt").asText()).isNotBlank();
    }

    /** 工具阶段模型失败后按 outbox 重试；重试成功不产生重复审批或产物。 */
    @Test
    void toolStageFailure_isRetriedAndCompletesOnce() {
        Fixture fixture = fixture("tenant-a", true);
        // M04：429/5xx 由 FailoverChatModel 在同一调用内退避重试；本用例验证 outbox 级重试，
        // 因此注入不可重试的协议错误（400），单模型链耗尽后按 ADR-0021 §5.3 返回统一 503，
        // 避免同一次调用内自动恢复。
        stub.forceStatus(400);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("RETRY_WAIT");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("PENDING");
        assertThat(stageStatus(fixture.runId(), "RETRIEVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("FAILED");
        assertThat(stageErrorCode(fixture.runId(), "TOOL")).isEqualTo("RICCON-MODEL-503");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("FAILED");
        stub.reset();
        makeOutboxAvailable(fixture.eventId());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("WAITING_APPROVAL");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("DONE");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("WAITING_APPROVAL");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("RUNNING");
        assertThat(approvalCount(fixture.runId(), "PENDING")).isEqualTo(1);

        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("SUCCEEDED");
        assertThat(approvalCount(fixture.runId(), "PENDING")).isZero();
        assertThat(approvalCount(fixture.runId(), "APPROVED")).isEqualTo(1);
        assertThat(artifactCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(1);
        assertThat(citationCount()).isEqualTo(2);
    }

    /** 报告生成失败：任务失败、保留已成功阶段、不产生产物且不再自动重试已消费的审批。 */
    @Test
    void generationFailure_marksTaskFailedWithoutArtifact() {
        Fixture fixture = fixture("tenant-a", true);
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        stub.forceStatus(500);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(resumeEvent)).isEqualTo("DEAD");
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("FAILED");
        assertThat(stageStatus(fixture.runId(), "TOOL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "GENERATION")).isEqualTo("FAILED");
        assertThat(stageErrorCode(fixture.runId(), "GENERATION")).isEqualTo("RICCON-MODEL-503");
        assertThat(taskFailureCode(fixture.taskId())).isEqualTo("RICCON-MODEL-503");
        assertThat(mcpInvocationCount(fixture.runId())).isEqualTo(1);
        assertThat(artifactCount()).isZero();
    }

    /** 生成失败后从 TOOL 阶段重试：新审批、新阶段尝试，最终只产生一个产物。 */
    @Test
    void retryFromToolStage_afterGenerationFailure_createsSingleArtifact() {
        Fixture fixture = fixture("tenant-a", true);
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID firstApproval = approvalId(fixture.runId());
        approve(fixture.userId(), firstApproval);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), firstApproval);
        stub.forceStatus(500);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("FAILED");

        // 模拟 API 重试：运行回到可领取状态，从 TOOL 阶段以 attempt=2 重新审批。
        jdbcClient.sql("""
                        UPDATE agent_run
                           SET status = 'PENDING', attempt = 2, current_stage = 'TOOL',
                               error_code = NULL, error_message = NULL, finished_at = NULL
                         WHERE id = :id
                        """).param("id", UuidBinary.toBytes(fixture.runId())).update();
        stub.reset();
        publishRequested(fixture, "TOOL", 2);

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(runStatus(fixture.runId())).isEqualTo("WAITING_APPROVAL");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("WAITING_APPROVAL");
        assertThat(approvalCount(fixture.runId(), "PENDING")).isEqualTo(1);
        assertThat(stageCount(fixture.runId(), "TOOL")).isEqualTo(2);

        UUID secondApproval = approvalId(fixture.runId());
        assertThat(secondApproval).isNotEqualTo(firstApproval);
        approve(fixture.userId(), secondApproval);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), secondApproval);
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("SUCCEEDED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("SUCCEEDED");
        assertThat(artifactCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(1);
        assertThat(citationCount()).isEqualTo(2);
        assertThat(stageCount(fixture.runId(), "GENERATION")).isEqualTo(1);
    }

    /** 检索无命中：任务失败、不进入工具与生成，也不产生审批。 */
    @Test
    void retrievalWithoutSources_failsWithoutApprovalOrArtifact() {
        Fixture fixture = fixture("tenant-a", false);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("FAILED");
        assertThat(taskStatus(fixture.taskId())).isEqualTo("FAILED");
        assertThat(taskFailureCode(fixture.taskId())).isEqualTo("RICCON-RESEARCH-404");
        assertThat(stageStatus(fixture.runId(), "RETRIEVAL")).isEqualTo("FAILED");
        assertThat(stageErrorCode(fixture.runId(), "RETRIEVAL")).isEqualTo("RICCON-RESEARCH-404");
        assertThat(outboxStatus(fixture.eventId())).isEqualTo("DEAD");
        assertThat(approvalCount(fixture.runId(), "PENDING")).isZero();
        assertThat(stub.requestCount()).isZero();
        assertThat(artifactCount()).isZero();
    }

    /** 检索来源写入截断片段：审批前可见、单行、不超过 200 字符且不落全文。 */
    @Test
    void retrievalDetails_includeTruncatedSnippetBeforeApproval() {
        Fixture fixture = fixture("tenant-a", true);
        String longContent = "市场增长\n第二段\t包含重复空白 " + "增".repeat(260);
        jdbcClient
                .sql("UPDATE knowledge_chunk SET content = :content WHERE document_id = :documentId")
                .param("content", longContent)
                .param("documentId", UuidBinary.toBytes(fixture.documentId()))
                .update();

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runStatus(fixture.runId())).isEqualTo("WAITING_APPROVAL");
        assertThat(stageStatus(fixture.runId(), "RETRIEVAL")).isEqualTo("SUCCEEDED");
        assertThat(stageStatus(fixture.runId(), "APPROVAL")).isEqualTo("RUNNING");
        JsonNode retrieval = objectMapper.readTree(stageDetails(fixture.runId(), "RETRIEVAL"));
        JsonNode source = retrieval.path("sources").get(0);
        String snippet = source.path("snippet").asText();
        assertThat(snippet).hasSize(RunStageDetails.MAX_SOURCE_SNIPPET_LENGTH);
        assertThat(snippet).startsWith("市场增长 第二段 包含重复空白 增").endsWith("...");
        assertThat(snippet).doesNotContain("\n", "\t");
        assertThat(snippet).isNotEqualTo(longContent);
        assertThat(source.path("title").asText()).isEqualTo("市场资料");
        assertThat(source.path("chunkId").asText()).isNotBlank();
        assertThat(source.path("score").asDouble()).isGreaterThan(0.0);
        assertThat(retrieval.toString()).doesNotContain(longContent);
    }

    /** 重复投递恢复事件：运行已成功，不再次调用模型或写入版本/引用。 */
    @Test
    void duplicateResumeEvent_doesNotDuplicateArtifactVersionOrCitations() {
        Fixture fixture = fixture("tenant-a", true);
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        UUID resumeEvent = data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        assertThat(poller.pollOnce()).isEqualTo(1);
        int requestsAfterSuccess = stub.requestCount();
        String bodyBefore = readObject(currentVersion().storageKey());
        String approvalDetailsBefore = stageDetails(fixture.runId(), "APPROVAL");
        String decidedAtBefore = objectMapper
                .readTree(approvalDetailsBefore)
                .path("approvalDecidedAt")
                .asText();
        assertThat(decidedAtBefore).isNotBlank();

        jdbcClient.sql("""
                        UPDATE agent_run_outbox
                           SET status = 'PENDING', attempts = 0, lease_until = NULL,
                               lease_owner = NULL, available_at = NOW(6)
                         WHERE id = :id
                        """).param("id", UuidBinary.toBytes(resumeEvent)).update();

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(resumeEvent)).isEqualTo("DONE");
        assertThat(stub.requestCount()).isEqualTo(requestsAfterSuccess);
        assertThat(artifactCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(1);
        assertThat(citationCount()).isEqualTo(2);
        assertThat(readObject(currentVersion().storageKey())).isEqualTo(bodyBefore);
        // 重复 resume 不重写审批详情，首次写入的审批时间保持不变。
        assertThat(stageDetails(fixture.runId(), "APPROVAL")).isEqualTo(approvalDetailsBefore);
        assertThat(objectMapper
                        .readTree(stageDetails(fixture.runId(), "APPROVAL"))
                        .path("approvalDecidedAt")
                        .asText())
                .isEqualTo(decidedAtBefore);
    }

    /** 同一任务正文变化时沿用同一产物并递增版本，不产生第二个产物。 */
    @Test
    void changedContent_reusesArtifactAndCreatesNextVersion() {
        Fixture fixture = fixture("tenant-a", true);
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        assertThat(poller.pollOnce()).isEqualTo(1);

        ResearchTaskRecord task = tenantCall(
                "tenant-a",
                () -> researchRepository
                        .findTask(TenantId.of("tenant-a"), fixture.taskId())
                        .orElseThrow());
        ReportDocument document = new ReportDocument(
                "市场调研 · 市场调研报告",
                "# 市场调研 · 市场调研报告\n\n## 摘要\n\n更新后的分析。\n\n## 分析\n\n更新后的正文。\n\n"
                        + "## 引用清单\n\n1. [1] 市场资料（第 1 段）：更新片段。\n\n## 数据来源\n\n- 知识库检索：更新\n",
                "更新后的分析。",
                List.of(new ReportCitation(
                        1,
                        "KNOWLEDGE",
                        null,
                        fixture.documentId(),
                        null,
                        "市场资料",
                        "更新片段。",
                        com.riccon.platform.domain.chat.LocatorType.PARAGRAPH,
                        null,
                        null,
                        null,
                        1)));
        SavedArtifact saved = tenantCall(
                "tenant-a",
                () -> artifactWriter.write(
                        TenantId.of("tenant-a"), task, fixture.runId(), fixture.userId(), document, "trace-version"));

        assertThat(saved.created()).isTrue();
        assertThat(saved.version().version()).isEqualTo(2);
        assertThat(artifactCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(2);
        assertThat(citationCount()).isEqualTo(3);
    }

    /** 产物、版本与引用查询严格按租户隔离，跨租户不可见。 */
    @Test
    void artifactQueries_areTenantScoped() {
        Fixture fixture = fixture("tenant-a", true);
        assertThat(poller.pollOnce()).isEqualTo(1);
        UUID approvalId = approvalId(fixture.runId());
        approve(fixture.userId(), approvalId);
        data.publishResearchResume("tenant-a", fixture.runId(), fixture.taskId(), approvalId);
        assertThat(poller.pollOnce()).isEqualTo(1);

        ArtifactRecord artifact = currentArtifact();
        ArtifactVersionRecord version = currentVersion();
        TenantId other = TenantId.of("tenant-b");
        assertThat(TenantContext.callWithTenant(other, () -> artifactRepository.findByTask(other, fixture.taskId())))
                .isEmpty();
        assertThat(TenantContext.callWithTenant(other, () -> artifactRepository.findById(other, artifact.id())))
                .isEmpty();
        assertThat(TenantContext.callWithTenant(other, () -> artifactRepository.findCitations(other, version.id())))
                .isEmpty();
        assertThat(TenantContext.callWithTenant(other, () -> researchRepository.findTask(other, fixture.taskId())))
                .isEmpty();
    }

    /**
     * 构造租户、知识库、MCP 与报告任务。
     *
     * @param tenant 租户
     * @param withKnowledge 是否写入可命中的知识库分块
     * @return 夹具
     */
    private Fixture fixture(String tenant, boolean withKnowledge) {
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        UUID workspace = data.createWorkspace(tenant, user);
        UUID document = data.createKnowledgeDocument(tenant, "市场资料", null, user);
        if (withKnowledge) {
            data.createKnowledgeChunk(tenant, document, 1, "市场保持增长。", 1);
            jdbcClient
                    .sql("UPDATE knowledge_document SET status = 'INDEXED' WHERE tenant_id = :tenantId AND id = :id")
                    .param("tenantId", tenant)
                    .param("id", UuidBinary.toBytes(document))
                    .update();
        }
        UUID server = data.createMcpServer("riccon-builtin", TestMcpCommands.mockServer());
        UUID tool = data.createMcpTool(
                server,
                McpMockServer.TOOL_NAME,
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");
        data.grantMcpTool(tenant, tool);
        data.createModelCatalog(stub.url(), TestCredentials.API_KEY);
        TestDataFactory.ResearchPublished published =
                data.publishResearchRequested(tenant, workspace, user, "市场调研", tool);
        return new Fixture(published.runId(), published.taskId(), published.eventId(), user, document, workspace);
    }

    /**
     * 写入指定阶段与尝试号的 requested 事件，模拟 API 手动重试。
     *
     * @param fixture 夹具
     * @param stage 起始阶段
     * @param attempt 尝试号
     * @return 事件 ID
     */
    private UUID publishRequested(Fixture fixture, String stage, int attempt) {
        String payload = "{\"runId\":\"" + fixture.runId() + "\",\"researchTaskId\":\"" + fixture.taskId()
                + "\",\"stage\":\"" + stage + "\",\"attempt\":" + attempt + "}";
        return data.createOutbox(
                "tenant-a",
                fixture.runId(),
                EventTypes.AGENT_RUN_REQUESTED,
                payload,
                "retry:" + fixture.runId() + ":" + stage + ":" + attempt);
    }

    /** 阶段记录数量。 */
    private int stageCount(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(Integer.class)
                .single();
    }

    /** 批准审批。 */
    private void approve(UUID user, UUID approvalId) {
        jdbcClient
                .sql("""
                        UPDATE approval
                           SET status = 'APPROVED', decided_by = :userId, decided_at = NOW(6)
                         WHERE id = :id
                        """)
                .param("userId", UuidBinary.toBytes(user))
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
    }

    /** 拒绝审批。 */
    private void reject(UUID user, UUID approvalId) {
        jdbcClient
                .sql("""
                        UPDATE approval
                           SET status = 'REJECTED', decided_by = :userId, decided_at = NOW(6)
                         WHERE id = :id
                        """)
                .param("userId", UuidBinary.toBytes(user))
                .param("id", UuidBinary.toBytes(approvalId))
                .update();
    }

    /** 读取审批 ID。 */
    private UUID approvalId(UUID runId) {
        return jdbcClient
                .sql("SELECT id FROM approval WHERE run_id = :runId ORDER BY created_at DESC LIMIT 1")
                .param("runId", UuidBinary.toBytes(runId))
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .orElseThrow();
    }

    /** 运行状态。 */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /** 任务状态。 */
    private String taskStatus(UUID taskId) {
        return jdbcClient
                .sql("SELECT status FROM research_task WHERE id = :id")
                .param("id", UuidBinary.toBytes(taskId))
                .query(String.class)
                .single();
    }

    /** 任务失败码。 */
    private String taskFailureCode(UUID taskId) {
        return jdbcClient
                .sql("SELECT failure_code FROM research_task WHERE id = :id")
                .param("id", UuidBinary.toBytes(taskId))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** 阶段状态。 */
    private String stageStatus(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** 阶段错误码。 */
    private String stageErrorCode(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT error_code FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** 阶段详情 JSON。 */
    private String stageDetails(UUID runId, String stage) {
        return jdbcClient
                .sql("SELECT details FROM agent_run_stage WHERE run_id = :runId AND stage = :stage")
                .param("runId", UuidBinary.toBytes(runId))
                .param("stage", stage)
                .query(String.class)
                .single();
    }

    /** outbox 状态。 */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /** 让退避中的 outbox 事件立即可领取，测试不 sleep。 */
    private void makeOutboxAvailable(UUID eventId) {
        jdbcClient
                .sql("UPDATE agent_run_outbox SET available_at = NOW(6) WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .update();
    }

    /** 审批数量。 */
    private int approvalCount(UUID runId, String status) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM approval WHERE run_id = :runId AND status = :status")
                .param("runId", UuidBinary.toBytes(runId))
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    /** MCP 调用数量。 */
    private int mcpInvocationCount(UUID runId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(Integer.class)
                .single();
    }

    /** MCP 调用 ID。 */
    private String mcpInvocationId(UUID runId) {
        return jdbcClient
                .sql("SELECT id FROM mcp_invocation WHERE run_id = :runId")
                .param("runId", UuidBinary.toBytes(runId))
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .map(UUID::toString)
                .orElseThrow();
    }

    /** 审计数量。 */
    private int auditCount(String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE action = :action")
                .param("action", action)
                .query(Integer.class)
                .single();
    }

    /** 审计 JSON 详情。 */
    private String auditDetails(String action) {
        return jdbcClient
                .sql("SELECT details FROM audit_event WHERE action = :action ORDER BY occurred_at DESC LIMIT 1")
                .param("action", action)
                .query(String.class)
                .single();
    }

    /** 产物总数。 */
    private int artifactCount() {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM artifact")
                .query(Integer.class)
                .single();
    }

    /** 版本总数。 */
    private int versionCount() {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM artifact_version")
                .query(Integer.class)
                .single();
    }

    /** 引用总数。 */
    private int citationCount() {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM artifact_citation")
                .query(Integer.class)
                .single();
    }

    /** 当前产物。 */
    private ArtifactRecord currentArtifact() {
        return tenantCall(
                "tenant-a",
                () -> artifactRepository
                        .findByTask(TenantId.of("tenant-a"), taskIdFromSingleArtifact())
                        .orElseThrow());
    }

    /** 当前版本。 */
    private ArtifactVersionRecord currentVersion() {
        ArtifactRecord artifact = currentArtifact();
        return tenantCall(
                "tenant-a",
                () -> artifactRepository
                        .findVersion(TenantId.of("tenant-a"), artifact.id(), artifact.currentVersion())
                        .orElseThrow());
    }

    /** 单产物测试中读取任务 ID。 */
    private UUID taskIdFromSingleArtifact() {
        return jdbcClient
                .sql("SELECT research_task_id FROM artifact LIMIT 1")
                .query(byte[].class)
                .optional()
                .map(UuidBinary::fromBytes)
                .orElseThrow();
    }

    /** 读取对象存储正文。 */
    private String readObject(String key) {
        return s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .asUtf8String();
    }

    /** 在租户上下文中执行查询。 */
    private <T> T tenantCall(String tenant, java.util.function.Supplier<T> supplier) {
        return TenantContext.callWithTenant(TenantId.of(tenant), supplier);
    }

    /** 计算 SHA-256。 */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * 夹具。
     *
     * @param runId 运行
     * @param taskId 任务
     * @param eventId requested 事件
     * @param userId 用户
     * @param documentId 知识库文档
     * @param workspaceId 工作空间
     */
    private record Fixture(UUID runId, UUID taskId, UUID eventId, UUID userId, UUID documentId, UUID workspaceId) {}
}
