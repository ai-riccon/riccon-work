package com.riccon.worker.workflow.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.agent.repository.AgentContextRepository;
import com.riccon.worker.config.AgentProperties;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.engine.repository.WorkflowEngineRepository;
import com.riccon.worker.workflow.node.WorkflowNodeTestFixtures;
import com.riccon.worker.workflow.node.domain.WorkflowNodeAbortedException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeFailureException;
import com.riccon.worker.workflow.node.domain.WorkflowNodeResult;
import com.riccon.worker.workflow.node.domain.WorkflowNodeTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RETRIEVAL 节点执行器（ADR-0024 §4.4）。
 *
 * <p>验证检索以运行发起人为主体、{@code unrestricted=false}、按 topK 截断、引用快照写入
 * sourceVersion，以及失败/超时/取消的稳定映射。M05 ACL 谓词本身由 M05 套件覆盖。
 */
class RetrievalNodeExecutorTest {

    /** 检索仓储。 */
    private AgentContextRepository agentContextRepository;

    /** 引擎仓储。 */
    private WorkflowEngineRepository engineRepository;

    /** 执行器。 */
    private RetrievalNodeExecutor executor;

    /** 租户。 */
    private final TenantId tenantId = TenantId.of(WorkflowNodeTestFixtures.TENANT);

    /** 发起人。 */
    private final UUID requester = UUID.fromString("01930000-0000-7000-8000-000000000012");

    /** 文档 ID。 */
    private final UUID documentId = UUID.fromString("01930000-0000-7000-8000-000000000021");

    /** 分块 ID。 */
    private final UUID chunkId = UUID.fromString("01930000-0000-7000-8000-000000000022");

    /** 每个测试重建 mock 与执行器。 */
    @BeforeEach
    void setUp() {
        agentContextRepository = mock(AgentContextRepository.class);
        engineRepository = mock(WorkflowEngineRepository.class);
        executor = new RetrievalNodeExecutor(
                agentContextRepository,
                engineRepository,
                new WorkflowTemplateRenderer(),
                new AgentProperties(4, 5, 5, 2000, 60));
    }

    /** 检索以发起人 ACL 执行并固化引用。 */
    @Test
    @DisplayName("检索复用发起人 ACL 并固化引用")
    void execute_usesRequesterAclAndBuildsCitations() {
        when(agentContextRepository.search(
                        eq(tenantId), eq(requester), eq(false), eq("市场 报告"), anyInt(), eq(3)))
                .thenReturn(List.of(chunk(1, "第一段正文"), chunk(2, "第二段正文")));
        when(engineRepository.findKnowledgeChecksums(eq(tenantId), any()))
                .thenReturn(Map.of(documentId, "checksum-v1"));

        WorkflowNodeResult result = executor.execute(execution("{\"topic\":\"市场\"}", futureDeadline(), activeGuard()));

        verify(agentContextRepository)
                .search(tenantId, requester, false, "市场 报告", 60, 3);
        assertThat(result.output()).containsEntry("hitCount", 2);
        assertThat(result.outputSummary()).isEqualTo("检索命中 2 条");
        assertThat(result.inputSummary()).contains("市场 报告");
        assertThat(result.citations()).hasSize(2);
        WorkflowCitation first = result.citations().get(0);
        assertThat(first.ordinal()).isEqualTo(1);
        assertThat(first.sourceType()).isEqualTo(CitationSourceType.KNOWLEDGE);
        assertThat(first.knowledgeDocumentId()).isEqualTo(documentId);
        assertThat(first.chunkId()).isEqualTo(chunkId);
        assertThat(first.locatorType()).isEqualTo(LocatorType.PARAGRAPH);
        assertThat(first.title()).isEqualTo("文档标题");
        assertThat(first.snippet()).isEqualTo("第一段正文");
        assertThat(first.sourceVersion()).isEqualTo("checksum-v1");
    }

    /** 引用版本读取失败映射 WF_RETRIEVAL_FAILED。 */
    @Test
    @DisplayName("引用版本读取失败映射稳定错误码")
    void execute_checksumFailure_mapsCode() {
        when(agentContextRepository.search(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk(1, "正文")));
        when(engineRepository.findKnowledgeChecksums(eq(tenantId), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> executor.execute(execution("{\"topic\":\"市场\"}", futureDeadline(), activeGuard())))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_RETRIEVAL_FAILED));
    }

    /** topK 超出 1–20 失败。 */
    @Test
    @DisplayName("topK 越界失败")
    void execute_topKOutOfRange_fails() {
        WorkflowNodeExecution execution = WorkflowNodeTestFixtures.execution(
                graph("{\"queryTemplate\":\"q\",\"topK\":21}"),
                "r",
                "{}",
                Map.of(),
                List.of(),
                futureDeadline(),
                activeGuard());

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
    }

    /** 缺少查询模板失败。 */
    @Test
    @DisplayName("缺少查询模板失败")
    void execute_missingQueryTemplate_fails() {
        WorkflowNodeExecution execution = WorkflowNodeTestFixtures.execution(
                graph("{\"topK\":3}"),
                "r",
                "{}",
                Map.of(),
                List.of(),
                futureDeadline(),
                activeGuard());

        assertThatThrownBy(() -> executor.execute(execution))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .hasMessageContaining("查询模板");
    }

    /** 检索异常映射 WF_RETRIEVAL_FAILED。 */
    @Test
    @DisplayName("检索异常映射稳定错误码")
    void execute_searchFailure_mapsCode() {
        when(agentContextRepository.search(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> executor.execute(execution("{\"topic\":\"市场\"}", futureDeadline(), activeGuard())))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_RETRIEVAL_FAILED));
    }

    /** 缺少 topK 配置失败（运行入口 fail-closed）。 */
    @Test
    @DisplayName("缺少 topK 配置失败")
    void execute_missingTopK_fails() {
        assertThatThrownBy(() -> executor.execute(executionWithoutTopK()))
                .isInstanceOf(WorkflowNodeFailureException.class)
                .satisfies(error -> assertThat(((WorkflowNodeFailureException) error).errorCode())
                        .isEqualTo(WorkflowErrorCode.WF_INVALID_GRAPH));
    }

    /** 已过期截止时间在检索前失败。 */
    @Test
    @DisplayName("截止时间过期失败")
    void execute_expiredDeadline_fails() {
        assertThatThrownBy(() -> executor.execute(execution("{\"topic\":\"市场\"}", expiredDeadline(), activeGuard())))
                .isInstanceOf(WorkflowNodeTimeoutException.class);
    }

    /** 取消守卫在检索前失败。 */
    @Test
    @DisplayName("取消守卫中断检索")
    void execute_cancelledGuard_aborts() {
        assertThatThrownBy(() -> executor.execute(execution(
                        "{\"topic\":\"市场\"}",
                        futureDeadline(),
                        WorkflowNodeTestFixtures.guard(false, true))))
                .isInstanceOf(WorkflowNodeAbortedException.class);
    }

    /**
     * 构造检索命中。
     *
     * @param ordinal 序号
     * @param content 正文
     * @return 命中
     */
    private RetrievedChunk chunk(int ordinal, String content) {
        return new RetrievedChunk(
                ordinal,
                CitationSourceType.KNOWLEDGE,
                null,
                documentId,
                chunkId,
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                ordinal,
                "文档标题",
                content,
                0.9);
    }

    /**
     * 构造执行上下文。
     *
     * @param inputs 运行输入
     * @param deadline 截止时间
     * @param guard 守卫
     * @return 上下文
     */
    private WorkflowNodeExecution execution(
            String inputs,
            com.riccon.worker.workflow.node.domain.WorkflowDeadline deadline,
            com.riccon.worker.workflow.node.domain.WorkflowNodeGuard guard) {
        return WorkflowNodeTestFixtures.execution(
                graph("{\"queryTemplate\":\"{{input.topic}} 报告\",\"topK\":3}"),
                "r",
                inputs,
                Map.of(),
                List.of(),
                deadline,
                guard);
    }

    /**
     * 缺少 topK 的执行上下文。
     *
     * @return 上下文
     */
    private WorkflowNodeExecution executionWithoutTopK() {
        return WorkflowNodeTestFixtures.execution(
                graph("{\"queryTemplate\":\"q\"}"),
                "r",
                "{}",
                Map.of(),
                List.of(),
                futureDeadline(),
                activeGuard());
    }

    /**
     * 检索图。
     *
     * @param config 检索配置
     * @return 图 JSON
     */
    private static String graph(String config) {
        return "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"r\",\"type\":\"RETRIEVAL\",\"name\":\"检索\",\"config\":" + config + "},"
                + "{\"id\":\"e\",\"type\":\"END\",\"name\":\"结束\",\"config\":{\"resultFrom\":\"r\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"r\",\"target\":\"e\"}]}";
    }

    /**
     * 未过期截止时间。
     *
     * @return 截止时间
     */
    private static com.riccon.worker.workflow.node.domain.WorkflowDeadline futureDeadline() {
        return WorkflowNodeTestFixtures.futureDeadline();
    }

    /**
     * 已过期截止时间。
     *
     * @return 截止时间
     */
    private static com.riccon.worker.workflow.node.domain.WorkflowDeadline expiredDeadline() {
        return WorkflowNodeTestFixtures.expiredDeadline();
    }

    /**
     * 正常守卫。
     *
     * @return 守卫
     */
    private static com.riccon.worker.workflow.node.domain.WorkflowNodeGuard activeGuard() {
        return WorkflowNodeTestFixtures.activeGuard();
    }
}
