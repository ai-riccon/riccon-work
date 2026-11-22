package com.riccon.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M20 F-004 成本分页参数契约（IT-MODEL-108b-CP）。
 *
 * <p>读取 {@code contracts/openapi.yaml}，锁定 {@code components.parameters.CostPage} 的 name/in/required
 * 与 {@code minimum: 1}、{@code maximum: 200}、{@code default: 1}，并断言它恰好被三个成本端点引用、
 * 不存在第四处。测试只解析 YAML，不启动 Spring、不调用业务端点；配额/成本的其余契约仍由
 * {@link ModelContract108bTest} 覆盖。断言强度由 {@code maximum} 放宽/删除的内存副本用例自证，
 * 不修改契约文件。
 */
class CostPageContractTest {

    /** OpenAPI 路径项中的 HTTP 方法键，用于区分操作与 {@code parameters} 等结构键。 */
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** 成本分页参数的组件引用。 */
    private static final String COST_PAGE_REF = "#/components/parameters/CostPage";

    /** 期望引用 CostPage 的三个成本端点（方法 + 路径，按实际契约）。 */
    private static final List<String> EXPECTED_REFERENCES =
            List.of("GET /api/v1/costs", "GET /api/v1/costs/invocations", "GET /api/v1/admin/costs");

    /** 契约文档。 */
    private final Map<String, Object> document = OpenApiContract.load();

    /** CostPage 定义必须是可选 query 整数，边界 1..200、默认 1（ADR-0022 §2.4/§2.5）。 */
    @Test
    @DisplayName("IT-MODEL-108b-CP-1：CostPage 为 query 可选整数，1..200 默认 1")
    void costPage_definition_matchesContractBoundary() {
        assertThat(costPageViolations(costPageParameter()))
                .as("CostPage 参数边界与 ADR-0022 §2.4/§2.5 一致")
                .isEmpty();
    }

    /** CostPage 恰好被三个成本端点引用；定义自身不再计入，也不允许第四处引用。 */
    @Test
    @DisplayName("IT-MODEL-108b-CP-2：CostPage 恰好被三个成本端点引用且无第四处")
    void costPage_references_exactlyThreeCostEndpoints() {
        ReferenceScan scan = scanCostPageReferences();
        // 先断言原始 $ref 计数，避免同一操作写两次仍因位置集合相同而漏检。
        assertThat(scan.occurrences()).as("CostPage 的 $ref 原始出现次数").isEqualTo(3);
        assertThat(scan.locations())
                .as("CostPage 的引用端点（定义本身不计入）")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_REFERENCES);
    }

    /** maximum 被放宽或删除时同一组断言必须失败；用内存副本验证，不修改 YAML。 */
    @Test
    @DisplayName("IT-MODEL-108b-CP-3：maximum 放宽或删除时断言必须失败")
    void costPageGuard_relaxedMaximum_fails() {
        Map<String, Object> relaxed = copyOfCostPage();
        OpenApiContract.map(relaxed.get("schema")).put("maximum", 500);
        assertThat(costPageViolations(relaxed))
                .as("maximum=500 必须被拒绝")
                .anyMatch(violation -> violation.contains("schema.maximum"));

        Map<String, Object> removed = copyOfCostPage();
        OpenApiContract.map(removed.get("schema")).remove("maximum");
        assertThat(costPageViolations(removed))
                .as("删除 maximum 必须被拒绝")
                .anyMatch(violation -> violation.contains("schema.maximum"));
    }

    /**
     * 读取 {@code components.parameters.CostPage}。
     *
     * @return 参数定义；缺失时抛错而不是让断言静默通过
     */
    private Map<String, Object> costPageParameter() {
        Map<String, Object> parameters = OpenApiContract.map(
                OpenApiContract.map(document.get("components")).get("parameters"));
        Map<String, Object> parameter = OpenApiContract.map(parameters.get("CostPage"));
        if (parameter.isEmpty()) {
            throw new IllegalStateException("components.parameters.CostPage 不存在");
        }
        return parameter;
    }

    /**
     * 复制 CostPage 定义，schemas 一层深拷贝，供负向用例安全修改。
     *
     * @return 可修改的参数副本
     */
    private Map<String, Object> copyOfCostPage() {
        Map<String, Object> copy = new LinkedHashMap<>(costPageParameter());
        copy.put("schema", new LinkedHashMap<>(OpenApiContract.map(copy.get("schema"))));
        return copy;
    }

    /**
     * 校验 CostPage 的参数位置与 schema 边界，返回全部违规项。
     *
     * @param parameter 参数定义
     * @return 违规描述；空列表表示符合契约
     */
    private static List<String> costPageViolations(Map<String, Object> parameter) {
        List<String> violations = new ArrayList<>();
        expectValue(violations, "name", parameter.get("name"), "page");
        expectValue(violations, "in", parameter.get("in"), "query");
        if (!Boolean.FALSE.equals(parameter.get("required"))) {
            violations.add("required 必须为 false，实际为 " + parameter.get("required"));
        }
        Map<String, Object> schema = OpenApiContract.map(parameter.get("schema"));
        expectValue(violations, "schema.type", schema.get("type"), "integer");
        expectNumber(violations, "schema.minimum", schema.get("minimum"), 1);
        expectNumber(violations, "schema.maximum", schema.get("maximum"), 200);
        expectNumber(violations, "schema.default", schema.get("default"), 1);
        return violations;
    }

    /**
     * 记录一个取值不符的违规项。
     *
     * @param violations 违规收集器
     * @param field 字段路径
     * @param actual 实际值
     * @param expected 期望值
     */
    private static void expectValue(List<String> violations, String field, Object actual, Object expected) {
        if (!expected.equals(actual)) {
            violations.add(field + " 期望 " + expected + "，实际为 " + actual);
        }
    }

    /**
     * 记录一个数值不符的违规项；接受 YAML 解析出的任意 Number 类型。
     *
     * @param violations 违规收集器
     * @param field 字段路径
     * @param actual 实际值
     * @param expected 期望整数
     */
    private static void expectNumber(List<String> violations, String field, Object actual, int expected) {
        if (!(actual instanceof Number number) || number.doubleValue() != expected) {
            violations.add(field + " 期望 " + expected + "，实际为 " + actual);
        }
    }

    /**
     * 扫描全部路径与操作，统计 CostPage 的参数 {@code $ref}。
     *
     * <p>路径级 {@code parameters} 也计入（记为 {@code PATH <path>}），避免把引用从操作挪到路径级后
     * 仍被误判为三处；{@code components.parameters} 中的定义本身不参与扫描。
     *
     * @return 引用位置与原始出现次数
     */
    private ReferenceScan scanCostPageReferences() {
        Map<String, Object> paths = OpenApiContract.map(document.get("paths"));
        List<String> locations = new ArrayList<>();
        int occurrences = 0;
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> item = OpenApiContract.map(path.getValue());
            int pathLevel = costPageRefCount(item);
            if (pathLevel > 0) {
                occurrences += pathLevel;
                locations.add("PATH " + path.getKey());
            }
            for (Map.Entry<String, Object> node : item.entrySet()) {
                if (!HTTP_METHODS.contains(node.getKey())) {
                    continue;
                }
                int operationLevel = costPageRefCount(OpenApiContract.map(node.getValue()));
                if (operationLevel > 0) {
                    occurrences += operationLevel;
                    locations.add(node.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        return new ReferenceScan(List.copyOf(locations), occurrences);
    }

    /**
     * 统计一个路径项或操作 {@code parameters} 列表中指向 CostPage 的 {@code $ref} 数量。
     *
     * @param node 路径项或操作
     * @return 引用次数
     */
    private static int costPageRefCount(Map<String, Object> node) {
        Object parameters = node.get("parameters");
        if (!(parameters instanceof List<?> list)) {
            return 0;
        }
        int count = 0;
        for (Object parameter : list) {
            if (COST_PAGE_REF.equals(OpenApiContract.map(parameter).get("$ref"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 引用扫描结果。
     *
     * @param locations 去重后的引用位置，格式为 {@code METHOD /path} 或 {@code PATH /path}
     * @param occurrences {@code $ref} 原始出现次数
     */
    private record ReferenceScan(List<String> locations, int occurrences) {}
}
