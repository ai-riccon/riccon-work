package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalDecision;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import com.riccon.platform.domain.workflow.WorkflowConditionOperator;
import com.riccon.platform.domain.workflow.WorkflowEdgeBranch;
import com.riccon.platform.domain.workflow.WorkflowErrorCode;
import com.riccon.platform.domain.workflow.WorkflowNodeStatus;
import com.riccon.platform.domain.workflow.WorkflowNodeType;
import com.riccon.platform.domain.workflow.WorkflowResultStatus;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import com.riccon.platform.domain.workflow.WorkflowRunStatus;
import com.riccon.platform.domain.workflow.WorkflowTemplateSource;
import com.riccon.platform.domain.workflow.WorkflowTemplateVisibility;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M06 编排 Studio 契约（{@code 1.6.0-m06}，ADR-0024 §7）。
 *
 * <p>只读 {@code contracts/openapi.yaml}，断言：版本与 117 条路径；23 个 M06 operation 的真实
 * operationId/权限/CSRF/幂等键（幂等键仅运行/重试/审批决定，create/duplicate/publish 不承诺键级重放），
 * validate 的可选范围参数；节点/状态/范围/错误码等封闭枚举与 {@code platform-domain} 完全一致；
 * {@code WorkflowEdge.branch} 是字符串枚举而非布尔；{@code WorkflowNodeConfig.actionKind/targetNodeKey}、
 * {@code WorkflowApproval.dataScopeSummary}、{@code WorkflowRun.updatedAt/counts}、
 * {@code WorkflowNodeRun.inputSummary} 与拒绝理由条件必填存在；M06b 模板字段只读预留。契约由 T01 独占
 * 写入，本测试不修改 contracts。
 */
class WorkflowContractM06Test {

    /** 契约根。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** 版本、117 条路径、23 个 M06 operation 与 deferred 边界。 */
    @Test
    @DisplayName("#contractVersionAndWorkflowOperations")
    void contractVersionAndWorkflowOperations() {
        assertThat(OpenApiContract.map(document.get("info")).get("version")).isEqualTo("1.6.0-m06");
        assertThat(OpenApiContract.map(document.get("paths"))).hasSize(117);

        List<String[]> operations = List.of(
                new String[] {"get", "/api/v1/workflows", "listWorkflows", "workflow.read"},
                new String[] {"post", "/api/v1/workflows", "createWorkflow", "workflow.edit"},
                new String[] {"get", "/api/v1/workflows/runtime-limits", "getWorkflowRuntimeLimits", "workflow.read"},
                new String[] {"get", "/api/v1/workflows/{workflowId}", "getWorkflow", "workflow.read"},
                new String[] {"put", "/api/v1/workflows/{workflowId}/draft", "saveWorkflowDraft", "workflow.edit"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/duplicate", "duplicateWorkflow", "workflow.edit"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/validate", "validateWorkflow", "workflow.edit"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/publish", "publishWorkflow", "workflow.publish"},
                new String[] {"get", "/api/v1/workflows/{workflowId}/versions", "listWorkflowVersions", "workflow.read"
                },
                new String[] {"post", "/api/v1/workflows/{workflowId}/disable", "disableWorkflow", "workflow.publish"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/enable", "enableWorkflow", "workflow.publish"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/transfer", "transferWorkflow", "workflow.manage"},
                new String[] {
                    "get", "/api/v1/workflows/{workflowId}/runs", "listWorkflowRunsForWorkflow", "workflow.read"
                },
                new String[] {"post", "/api/v1/workflows/{workflowId}/runs", "createWorkflowRun", "workflow.run"},
                new String[] {"get", "/api/v1/workflow-runs", "listWorkflowRuns", "workflow.read"},
                new String[] {"get", "/api/v1/workflow-runs/{workflowRunId}", "getWorkflowRun", "workflow.read"},
                new String[] {
                    "post", "/api/v1/workflow-runs/{workflowRunId}/cancel", "cancelWorkflowRun", "workflow.run"
                },
                new String[] {
                    "post", "/api/v1/workflow-runs/{workflowRunId}/retry", "retryWorkflowRunNode", "workflow.run"
                },
                new String[] {"get", "/api/v1/workflow-approvals", "listWorkflowApprovals", "workflow.approve"},
                new String[] {
                    "get", "/api/v1/workflow-approvals/{workflowApprovalId}", "getWorkflowApproval", "workflow.approve"
                },
                new String[] {
                    "post",
                    "/api/v1/workflow-approvals/{workflowApprovalId}/decision",
                    "decideWorkflowApproval",
                    "workflow.approve"
                },
                new String[] {"get", "/api/v1/workflow-templates", "listWorkflowTemplates", "workflow.read"},
                new String[] {
                    "post", "/api/v1/workflow-templates/{templateId}/copy", "copyWorkflowTemplate", "workflow.edit"
                });

        for (String[] expected : operations) {
            Map<String, Object> operation = OpenApiContract.operation(document, expected[1], expected[0]);
            assertThat(operation.get("operationId"))
                    .as(expected[0] + " " + expected[1])
                    .isEqualTo(expected[2]);
            assertThat(OpenApiContract.permission(operation)).as(expected[2]).isEqualTo(expected[3]);
            assertThat(OpenApiContract.deferred(operation))
                    .as("M06 operation must not be deferred: " + expected[2])
                    .isNull();
        }
        assertThat(operations).hasSize(23);

        // 写操作必须显式声明 CSRF（$ref）；只有运行/重试/审批决定保留必需 Idempotency-Key（键级幂等）。
        for (String[] expected : List.of(
                new String[] {"post", "/api/v1/workflows"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/publish"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/runs"},
                new String[] {"post", "/api/v1/workflow-runs/{workflowRunId}/retry"},
                new String[] {"post", "/api/v1/workflow-approvals/{workflowApprovalId}/decision"})) {
            Map<String, Object> operation = OpenApiContract.operation(document, expected[1], expected[0]);
            assertThat(OpenApiContract.referencesParameter(operation, "CsrfToken"))
                    .as(expected[1])
                    .isTrue();
        }
        for (String[] expected : List.of(
                new String[] {"post", "/api/v1/workflows/{workflowId}/runs"},
                new String[] {"post", "/api/v1/workflow-runs/{workflowRunId}/retry"},
                new String[] {"post", "/api/v1/workflow-approvals/{workflowApprovalId}/decision"})) {
            Map<String, Object> operation = OpenApiContract.operation(document, expected[1], expected[0]);
            assertThat(declaresParameter(operation, "Idempotency-Key"))
                    .as(expected[1])
                    .isTrue();
            assertThat(parameter(operation, "Idempotency-Key").get("required"))
                    .as(expected[1])
                    .isEqualTo(true);
        }

        // M06 幂等收敛（F-001）：create/duplicate/publish 不声明必需幂等键，publish 描述资源级幂等。
        for (String[] expected : List.of(
                new String[] {"post", "/api/v1/workflows"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/duplicate"},
                new String[] {"post", "/api/v1/workflows/{workflowId}/publish"})) {
            Map<String, Object> operation = OpenApiContract.operation(document, expected[1], expected[0]);
            assertThat(declaresParameter(operation, "Idempotency-Key"))
                    .as(expected[1])
                    .isFalse();
        }
        assertThat(String.valueOf(OpenApiContract.operation(document, "/api/v1/workflows/{workflowId}/publish", "post")
                        .get("description")))
                .contains("graph_sha256", "资源级幂等");
        assertThat(String.valueOf(OpenApiContract.operation(document, "/api/v1/workflows", "post")
                        .get("description")))
                .contains("不承诺 Idempotency-Key 键级重放");

        // VALIDATE 范围（F-002）：可选 runScope/departmentId，runScope 引用封闭枚举。
        Map<String, Object> validate =
                OpenApiContract.operation(document, "/api/v1/workflows/{workflowId}/validate", "post");
        Map<String, Object> runScope = parameter(validate, "runScope");
        assertThat(runScope.get("required")).isEqualTo(false);
        assertThat(OpenApiContract.map(runScope.get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/WorkflowRunScope");
        Map<String, Object> departmentId = parameter(validate, "departmentId");
        assertThat(departmentId.get("required")).isEqualTo(false);
        assertThat(OpenApiContract.map(departmentId.get("schema")).get("type")).isEqualTo("string");
        assertThat(OpenApiContract.map(departmentId.get("schema")).get("format"))
                .isEqualTo("uuid");
        assertThat(String.valueOf(validate.get("description"))).contains("复用 PUBLISH 的范围校验", "只读");

        List<?> deferredEntries =
                document.get("x-riccon-deferred") instanceof List<?> list ? List.copyOf(list) : List.of();
        List<String> deferredIds = deferredEntries.stream()
                .map(OpenApiContract::map)
                .map(entry -> String.valueOf(entry.get("id")))
                .toList();
        assertThat(deferredIds).contains("workflow-template-sharing", "workflow-code-execution");

        // 运行历史/详情错误码：不可见对象 404、状态冲突 409、关闭/引擎不可用 503。
        Map<String, Object> runDetail =
                OpenApiContract.operation(document, "/api/v1/workflow-runs/{workflowRunId}", "get");
        assertThat(OpenApiContract.response(runDetail, "404")).isNotEmpty();
        Map<String, Object> publish =
                OpenApiContract.operation(document, "/api/v1/workflows/{workflowId}/publish", "post");
        assertThat(OpenApiContract.response(publish, "409")).isNotEmpty();
        Map<String, Object> createRun =
                OpenApiContract.operation(document, "/api/v1/workflows/{workflowId}/runs", "post");
        assertThat(OpenApiContract.response(createRun, "429")).isNotEmpty();
        assertThat(OpenApiContract.response(createRun, "503")).isNotEmpty();
    }

    /** 节点/边/条件枚举与 platform-domain 完全一致；branch 是字符串枚举而不是布尔。 */
    @Test
    @DisplayName("#graphVocabulary")
    void graphVocabulary() {
        assertEnumMatches("WorkflowNodeType", WorkflowNodeType.class);
        assertThat(OpenApiContract.stringList(OpenApiContract.map(OpenApiContract.schema(document, "WorkflowNodeType"))
                        .get("enum")))
                .containsExactlyInAnyOrder("START", "END", "RETRIEVAL", "MODEL", "MCP_TOOL", "APPROVAL", "CONDITION")
                .doesNotContain("CODE", "SCRIPT", "HTTP", "PARALLEL", "LOOP", "SUBFLOW", "CRON");
        assertEnumMatches("WorkflowNodeStatus", WorkflowNodeStatus.class);
        assertEnumMatches("WorkflowRunStatus", WorkflowRunStatus.class);
        assertEnumMatches("WorkflowRunScope", WorkflowRunScope.class);
        assertEnumMatches("WorkflowResultStatus", WorkflowResultStatus.class);

        Map<String, Object> edge = OpenApiContract.schema(document, "WorkflowEdge");
        Map<String, Object> branch =
                OpenApiContract.map(OpenApiContract.map(edge.get("properties")).get("branch"));
        assertThat(branch.get("type")).isEqualTo(List.of("string", "null"));
        @SuppressWarnings("unchecked")
        List<Object> branchEnum = (List<Object>) branch.get("enum");
        assertThat(branchEnum).hasSize(3);
        assertThat(branchEnum.get(0)).isEqualTo("TRUE");
        assertThat(branchEnum.get(1)).isEqualTo("FALSE");
        assertThat(branchEnum.get(2)).isNull();
        assertThat(Arrays.stream(WorkflowEdgeBranch.values()).map(Enum::name).toList())
                .containsExactly("TRUE", "FALSE");

        Map<String, Object> condition = OpenApiContract.schema(document, "WorkflowCondition");
        Map<String, Object> operator = OpenApiContract.map(
                OpenApiContract.map(condition.get("properties")).get("operator"));
        assertThat(OpenApiContract.stringList(operator.get("enum")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(WorkflowConditionOperator.values())
                        .map(Enum::name)
                        .toList());
    }

    /** 节点配置 actionKind/targetNodeKey：审批解锁动作必须指向直接后继，M06b 不冒充。 */
    @Test
    @DisplayName("#workflowNodeConfig")
    void workflowNodeConfig() {
        Map<String, Object> config = OpenApiContract.schema(document, "WorkflowNodeConfig");
        Map<String, Object> properties = OpenApiContract.map(config.get("properties"));
        Map<String, Object> actionKind = OpenApiContract.map(properties.get("actionKind"));
        assertThat(OpenApiContract.oneOfReferences(actionKind, "WorkflowApprovalActionKind"))
                .isTrue();
        assertThat(String.valueOf(actionKind.get("description"))).contains("MCP_TOOL");

        Map<String, Object> targetNodeKey = OpenApiContract.map(properties.get("targetNodeKey"));
        assertThat(targetNodeKey.get("pattern")).isEqualTo("^[A-Za-z0-9_-]{1,64}$");
        assertThat(OpenApiContract.additionalPropertiesFalse(config)).isTrue();

        assertEnumMatches("WorkflowApprovalActionKind", WorkflowApprovalActionKind.class);
        assertEnumMatches("WorkflowApprovalDecision", WorkflowApprovalDecision.class);
        assertEnumMatches("WorkflowApprovalStatus", WorkflowApprovalStatus.class);
        assertEnumMatches("WorkflowErrorCode", WorkflowErrorCode.class);
        assertEnumMatches("WorkflowTemplateSource", WorkflowTemplateSource.class);
        assertEnumMatches("WorkflowTemplateVisibility", WorkflowTemplateVisibility.class);
    }

    /** 运行/节点/审批的 M06 schema 增补：updatedAt、counts、inputSummary、dataScopeSummary。 */
    @Test
    @DisplayName("#runNodeApprovalSchemas")
    void runNodeApprovalSchemas() {
        Map<String, Object> run = OpenApiContract.schema(document, "WorkflowRun");
        assertThat(OpenApiContract.required(run)).contains("updatedAt", "createdAt", "retentionExpiresAt");
        Map<String, Object> runProperties = OpenApiContract.map(run.get("properties"));
        for (String count : List.of("pendingApprovalCount", "approvedApprovalCount", "rejectedApprovalCount")) {
            assertThat(OpenApiContract.map(runProperties.get(count)).get("minimum"))
                    .as(count)
                    .isEqualTo(0);
        }
        assertThat(String.valueOf(
                        OpenApiContract.map(runProperties.get("updatedAt")).get("description")))
                .contains("轮询");

        Map<String, Object> nodeRun = OpenApiContract.schema(document, "WorkflowNodeRun");
        Map<String, Object> inputSummary = OpenApiContract.map(
                OpenApiContract.map(nodeRun.get("properties")).get("inputSummary"));
        assertThat(inputSummary.get("maxLength")).isEqualTo(2000);
        assertThat(OpenApiContract.required(nodeRun)).contains("status", "attempt");

        Map<String, Object> approval = OpenApiContract.schema(document, "WorkflowApproval");
        assertThat(OpenApiContract.required(approval)).contains("dataScopeSummary", "planSha256", "actionKind");
        assertThat(OpenApiContract.map(
                                OpenApiContract.map(approval.get("properties")).get("dataScopeSummary"))
                        .get("$ref"))
                .isEqualTo("#/components/schemas/WorkflowDataScopeSummary");
        Map<String, Object> dataScope = OpenApiContract.schema(document, "WorkflowDataScopeSummary");
        assertThat(OpenApiContract.required(dataScope)).contains("runScope", "documentReferenceCount");
        assertThat(OpenApiContract.required(dataScope)).doesNotContain("documentTitles", "excerpts");
    }

    /** 拒绝理由条件必填：decision=REJECT 时 comment 必须存在且 minLength=1。 */
    @Test
    @DisplayName("#rejectCommentRequired")
    void rejectCommentRequired() {
        Map<String, Object> request = OpenApiContract.schema(document, "WorkflowApprovalDecisionRequest");
        List<?> allOf = (List<?>) request.get("allOf");
        assertThat(allOf).isNotNull();
        Map<String, Object> conditional = OpenApiContract.map(allOf.stream()
                .filter(part -> OpenApiContract.map(part).containsKey("if"))
                .findFirst()
                .orElseThrow());
        Map<String, Object> condition = OpenApiContract.map(conditional.get("if"));
        assertThat(OpenApiContract.map(condition.get("properties"))).containsKey("decision");
        assertThat(OpenApiContract.stringList(condition.get("required"))).contains("decision");
        Map<String, Object> then = OpenApiContract.map(conditional.get("then"));
        assertThat(OpenApiContract.stringList(then.get("required"))).contains("comment");
        Map<String, Object> comment =
                OpenApiContract.map(OpenApiContract.map(then.get("properties")).get("comment"));
        assertThat(comment.get("minLength")).isEqualTo(1);
        assertThat(comment.get("maxLength")).isEqualTo(500);
    }

    /** M06b 模板字段只读预留：升级提示字段存在但 M06 无写入端点。 */
    @Test
    @DisplayName("#templateDeferredFields")
    void templateDeferredFields() {
        Map<String, Object> template = OpenApiContract.schema(document, "WorkflowTemplate");
        Map<String, Object> properties = OpenApiContract.map(template.get("properties"));
        assertThat(properties)
                .containsKeys("visibility", "builtInVersion", "latestBuiltInVersion", "upgradeAvailable", "source");
        assertThat(String.valueOf(
                        OpenApiContract.map(properties.get("upgradeAvailable")).get("description")))
                .contains("M06b");
        assertThat(OpenApiContract.operation(document, "/api/v1/workflow-templates", "get")
                        .get("operationId"))
                .isEqualTo("listWorkflowTemplates");
        // M06b 写端点不存在：模板路径上只有 get 与 copy，不提供 POST/PATCH/DELETE 模板本身。
        Map<String, Object> templates = OpenApiContract.pathItem(document, "/api/v1/workflow-templates");
        assertThat(templates).containsOnlyKeys("get");

        Map<String, Object> limits = OpenApiContract.schema(document, "WorkflowRuntimeLimits");
        assertThat(OpenApiContract.map(limits.get("properties"))).containsKey("disabledReason");
        assertThat(OpenApiContract.required(limits)).contains("featureEnabled", "resultMaxBytes");
    }

    /**
     * 按名称取操作参数定义（支持内联 name 或组件 {@code $ref} 的 {@code IdempotencyKey}）。
     *
     * @param operation 操作
     * @param name 参数名
     * @return 参数定义
     */
    private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
        String componentRef = "#/components/parameters/" + name.replace("-", "");
        Object parameters = operation.get("parameters");
        assertThat(parameters).isInstanceOf(List.class);
        for (Map<String, Object> candidate : ((List<?>) parameters)
                .stream()
                        .filter(Map.class::isInstance)
                        .map(OpenApiContract::map)
                        .toList()) {
            if (name.equals(candidate.get("name")) || componentRef.equals(candidate.get("$ref"))) {
                return candidate;
            }
        }
        throw new AssertionError("parameter not found: " + name);
    }

    /**
     * 判断操作是否声明了指定参数（内联 name 或组件 {@code $ref}）。
     *
     * @param operation 操作
     * @param name 参数名
     * @return 声明返回 true
     */
    private static boolean declaresParameter(Map<String, Object> operation, String name) {
        Object parameters = operation.get("parameters");
        if (!(parameters instanceof List<?> list)) {
            return false;
        }
        // 契约里既有内联参数（name: Idempotency-Key），也有组件引用（#/components/parameters/IdempotencyKey）。
        String componentRef = "#/components/parameters/" + name.replace("-", "");
        return list.stream()
                .filter(Map.class::isInstance)
                .map(OpenApiContract::map)
                .anyMatch(
                        parameter -> name.equals(parameter.get("name")) || componentRef.equals(parameter.get("$ref")));
    }

    /**
     * 断言契约枚举名与领域枚举名完全一致（顺序无关）。
     *
     * @param schemaName 契约 schema 名
     * @param type 领域枚举类型
     */
    private void assertEnumMatches(String schemaName, Class<? extends Enum<?>> type) {
        Map<String, Object> schema = OpenApiContract.schema(document, schemaName);
        assertThat(OpenApiContract.stringList(schema.get("enum")))
                .as(schemaName)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }
}
