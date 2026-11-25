package com.riccon.worker.workflow.node;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.workflow.engine.domain.WorkflowCitation;
import com.riccon.worker.workflow.engine.domain.WorkflowGraph;
import com.riccon.worker.workflow.engine.domain.WorkflowGraphJson;
import com.riccon.worker.workflow.node.domain.WorkflowDeadline;
import com.riccon.worker.workflow.node.domain.WorkflowNodeExecution;
import com.riccon.worker.workflow.node.domain.WorkflowNodeGuard;
import com.riccon.worker.workflow.node.domain.WorkflowNodeOutput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * 节点执行器单元测试夹具。
 *
 * <p>只构造最小图与执行上下文；不启动 Spring、不访问数据库。时间用固定时钟，取消/租约用可配置
 * 守卫，测试不依赖机器时间或线程调度。
 */
public final class WorkflowNodeTestFixtures {

    /** 测试租户。 */
    public static final String TENANT = "tenant-node-test";

    /** 固定时钟。 */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);

    /** JSON 解析。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化。 */
    private WorkflowNodeTestFixtures() {}

    /**
     * 构造执行上下文。
     *
     * @param graphJson 图 JSON
     * @param nodeKey 当前节点 key
     * @param inputsJson 运行输入
     * @param outputs 上游输出
     * @param citations 聚合引用
     * @param deadline 截止时间
     * @param guard 取消/租约守卫
     * @return 执行上下文
     */
    public static WorkflowNodeExecution execution(
            String graphJson,
            String nodeKey,
            String inputsJson,
            Map<String, WorkflowNodeOutput> outputs,
            List<WorkflowCitation> citations,
            WorkflowDeadline deadline,
            WorkflowNodeGuard guard) {
        WorkflowGraph graph = WorkflowGraphJson.parse(graphJson, 50);
        return new WorkflowNodeExecution(
                TenantId.of(TENANT),
                UUID.fromString("01930000-0000-7000-8000-000000000010"),
                UUID.fromString("01930000-0000-7000-8000-000000000011"),
                UUID.fromString("01930000-0000-7000-8000-000000000012"),
                "workflow-run-test",
                MAPPER.readTree(inputsJson),
                graph,
                graph.requireNode(nodeKey),
                outputs,
                0L,
                citations,
                deadline,
                guard);
    }

    /**
     * 构造未过期截止时间。
     *
     * @return 截止时间
     */
    public static WorkflowDeadline futureDeadline() {
        return new WorkflowDeadline(CLOCK.instant().plus(Duration.ofMinutes(5)), CLOCK);
    }

    /**
     * 构造已过期截止时间。
     *
     * @return 截止时间
     */
    public static WorkflowDeadline expiredDeadline() {
        return new WorkflowDeadline(CLOCK.instant().minusSeconds(1), CLOCK);
    }

    /**
     * 正常守卫。
     *
     * @return 守卫
     */
    public static WorkflowNodeGuard activeGuard() {
        return new WorkflowNodeGuard() {
            @Override
            public boolean lost() {
                return false;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    /**
     * 构造指定状态的守卫。
     *
     * @param lost 是否丢失
     * @param cancelled 是否取消
     * @return 守卫
     */
    public static WorkflowNodeGuard guard(boolean lost, boolean cancelled) {
        return new WorkflowNodeGuard() {
            @Override
            public boolean lost() {
                return lost;
            }

            @Override
            public boolean cancelled() {
                return cancelled;
            }
        };
    }

    /**
     * 模型输出视图。
     *
     * @param text 文本
     * @param classificationLabel 分类标签
     * @param summary 摘要
     * @return 输出
     */
    public static WorkflowNodeOutput modelOutput(String text, String classificationLabel, String summary) {
        var node = MAPPER.createObjectNode();
        node.put("text", text);
        if (classificationLabel == null) {
            node.putNull("classificationLabel");
        } else {
            node.put("classificationLabel", classificationLabel);
        }
        return new WorkflowNodeOutput(node, summary);
    }

    /**
     * 检索输出视图。
     *
     * @param hitCount 命中数
     * @param summary 摘要
     * @return 输出
     */
    public static WorkflowNodeOutput retrievalOutput(int hitCount, String summary) {
        var node = MAPPER.createObjectNode();
        node.put("hitCount", hitCount);
        return new WorkflowNodeOutput(node, summary);
    }
}
