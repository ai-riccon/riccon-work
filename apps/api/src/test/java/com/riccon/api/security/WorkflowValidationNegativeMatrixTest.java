package com.riccon.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.version.WorkflowVersionTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M06 校验器负向矩阵（REV-TASK-0221-001 F-004 的 T16a 承接部分）。
 *
 * <p>补齐 END 出边、START 入边、MODEL 审批动作后继、WRITE 风险工具审批前驱、发布阶段无其他可用审批人
 * 等分支的真实 HTTP/MySQL 负向断言。非法图保存必须 400 且不推进草稿版本，与 AC-02/18 的 fail-closed
 * 语义一致。</p>
 */
@Tag("security")
class WorkflowValidationNegativeMatrixTest extends WorkflowVersionTestSupport {

    /** 唯一后缀。 */
    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 调用保存草稿并返回响应。 */
    private ApiTestClient.ApiResponse save(LoginSession session, String workflowId, int version, String graph) {
        return client.put(
                "/api/v1/workflows/" + workflowId + "/draft",
                JSON.writeValueAsString(Map.of("version", version, "graph", JSON.readTree(graph))),
                session.sessionCookie(),
                session.csrfToken());
    }

    /** 断言保存非法图 400 且 detail 含期望问题码/片段，不推进版本。 */
    private void assertSaveRejected(Fixture fixture, String workflowId, String graph, String expectedFragment) {
        ApiTestClient.ApiResponse response = save(fixture.owner(), workflowId, 1, graph);
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo(WF_400);
        assertThat(client.json(response).path("detail").asText()).contains(expectedFragment);
        assertThat(getDetail(fixture.owner(), workflowId, 200)
                        .path("draftVersion")
                        .asInt())
                .isEqualTo(1);
    }

    /** END 出边与 START 入边必须在保存阶段阻断。 */
    @Test
    @DisplayName("F-004 END 出边与 START 入边负向")
    void endOutgoingAndStartIncomingAreRejected() {
        Fixture fixture = fixture("negstruct");
        String workflowId =
                createDraft(fixture.owner(), "结构负向-" + suffix()).path("id").asText();

        assertSaveRejected(fixture, workflowId, """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"model"},
                  {"id":"e2","source":"model","target":"end"},
                  {"id":"e3","source":"end","target":"model"}
                ]}
                """, "END node must not have outgoing edges");

        assertSaveRejected(fixture, workflowId, """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"model","type":"MODEL","name":"模型","config":{"promptTemplate":"x","outputKind":"TEXT"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"model"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"model"},
                  {"id":"e2","source":"model","target":"end"},
                  {"id":"e3","source":"model","target":"start"}
                ]}
                """, "START node must not have incoming edges");
    }

    /** MODEL 审批动作必须指向 MODEL 后继；WRITE 风险工具必须有 MCP_TOOL 审批前驱。 */
    @Test
    @DisplayName("F-004 MODEL 审批后继与 WRITE 工具审批前驱负向")
    void modelActionAndWriteToolRequireCorrectBinding() {
        Fixture fixture = fixture("negbind");
        String workflowId =
                createDraft(fixture.owner(), "绑定负向-" + suffix()).path("id").asText();
        UUID writeToolId = insertWriteTool(fixture.tenant());

        // 审批 actionKind=MODEL 但直接后继是 END → APPROVAL_CONFIG。
        assertSaveRejected(fixture, workflowId, """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"approval","type":"APPROVAL","name":"审批","config":{
                    "assigneeType":"ROLE","assigneeRole":"WORKFLOW_EDITOR","actionKind":"MODEL"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"approval"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"approval"},
                  {"id":"e2","source":"approval","target":"end"}
                ]}
                """, "MODEL approval requires a MODEL successor");

        // WRITE 风险工具没有审批前驱 → APPROVAL_CONFIG，且不发起工具调用（保存阶段阻断）。
        assertSaveRejected(fixture, workflowId, """
                {"schemaVersion":1,"nodes":[
                  {"id":"start","type":"START","name":"开始","config":{}},
                  {"id":"tool","type":"MCP_TOOL","name":"写工具","config":{"toolId":"%s"}},
                  {"id":"end","type":"END","name":"结束","config":{"resultFrom":"tool"}}
                ],"edges":[
                  {"id":"e1","source":"start","target":"tool"},
                  {"id":"e2","source":"tool","target":"end"}
                ]}
                """.formatted(writeToolId), "APPROVAL_CONFIG");
    }

    /** 发布阶段必须存在非发布者的可用审批人（ROLE/USER 两种解析路径）。 */
    @Test
    @DisplayName("F-004 发布阶段无其他可用审批人返回 NO_APPROVER")
    void publishRequiresActiveApproverOtherThanPublisher() {
        Fixture fixture = fixture("negapprover");
        String workflowId =
                createDraft(fixture.owner(), "审批人负向-" + suffix()).path("id").asText();
        saveDraft(fixture.owner(), workflowId, 1, graph(fixture.toolId(), userApprover(fixture.ownerId())));
        ApiTestClient.ApiResponse publish = client.post(
                "/api/v1/workflows/" + workflowId + "/publish",
                client.jsonBody("runScope", "ONLY_SELF"),
                fixture.owner().sessionCookie(),
                fixture.owner().csrfToken(),
                Map.of("Idempotency-Key", "neg-approver-" + UUID.randomUUID()));
        assertThat(publish.status()).as(publish.body()).isEqualTo(400);
        assertThat(client.json(publish).path("code").asText()).isEqualTo(WF_400);
        assertThat(client.json(publish).path("detail").asText()).contains("NO_APPROVER");
        assertThat(versionCount(fixture.tenant(), workflowId)).isZero();
    }

    /** 插入 WRITE 风险、免审批工具并授权给当前租户；用于验证风险推导必须强制审批。 */
    private UUID insertWriteTool(String tenant) {
        UUID serverId = UuidV7.generate();
        UUID toolId = UuidV7.generate();
        String suffix = suffix();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_server (id, code, name, transport, endpoint, status, created_at, updated_at)
                        VALUES (:id, :code, 'Mock WRITE MCP', 'HTTP', 'https://mcp.invalid/rpc', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("id", BinaryUuids.toBytes(serverId))
                .param("code", "neg-write-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool (
                            id, server_id, name, description, risk_level, requires_approval, status,
                            created_at, updated_at
                        ) VALUES (
                            :id, :serverId, :name, 'write tool without explicit approval flag', 'WRITE', FALSE, 'ACTIVE',
                            NOW(6), NOW(6)
                        )
                        """)
                .param("id", BinaryUuids.toBytes(toolId))
                .param("serverId", BinaryUuids.toBytes(serverId))
                .param("name", "write-" + suffix)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled, granted_at, updated_at)
                        VALUES (:tenant, :toolId, TRUE, NOW(6), NOW(6))
                        """)
                .param("tenant", tenant)
                .param("toolId", BinaryUuids.toBytes(toolId))
                .update();
        return toolId;
    }
}
