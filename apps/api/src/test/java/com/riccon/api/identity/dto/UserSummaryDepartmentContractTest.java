package com.riccon.api.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code UserSummaryResponse} 与 OpenAPI {@code UserSummary} 的部门字段契约。
 *
 * <p>M23 / M13 F-001：契约已声明可空的 {@code departmentId}/{@code departmentName}，实现必须实际下发；未分配部门
 * 输出 {@code null}，而不是省略、空串或伪造名称。测试直接读取仓库内契约，避免 DTO 与文档漂移；本任务只对齐实现，
 * 不修改契约。
 */
class UserSummaryDepartmentContractTest {

    /** 序列化与 JSON 断言。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 契约字段类型与实现输出一致。 */
    @Test
    @DisplayName("契约声明可空的 departmentId/departmentName，且实现输出同名字段")
    void openApiDeclaresNullableDepartmentFieldsMatchingImplementation() {
        Map<String, Object> summary = openApiUserSummary();

        Map<String, Object> departmentId = property(summary, "departmentId");
        assertThat(departmentId.get("type")).isEqualTo(List.of("string", "null"));
        assertThat(departmentId.get("format")).isEqualTo("uuid");

        Map<String, Object> departmentName = property(summary, "departmentName");
        assertThat(departmentName.get("type")).isEqualTo(List.of("string", "null"));

        // 两字段可空且不强制，实现按“未分配输出 null”的约定，不改变契约的 required 集合。
        List<Object> required = requiredFields(summary);
        assertThat(required).doesNotContain("departmentId", "departmentName");

        // SessionInfo.user 直接引用 UserSummary，因此会话摘要与账号摘要共用同一部门字段契约。
        Map<String, Object> sessionUser = property(openApiSchema("SessionInfo"), "user");
        assertThat(sessionUser.get("$ref")).isEqualTo("#/components/schemas/UserSummary");
    }

    /** 已分配部门时输出 ID 与名称。 */
    @Test
    @DisplayName("已分配部门序列化 departmentId/departmentName")
    void serializesDepartmentFieldsWhenAssigned() {
        UUID departmentId = UUID.randomUUID();
        UserSummaryResponse summary = new UserSummaryResponse(
                UUID.randomUUID(),
                "alice",
                "Alice",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.LOCAL,
                null,
                null,
                Instant.parse("2026-11-01T00:00:00Z"),
                departmentId,
                "研发部");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(summary));

        assertThat(json.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(json.path("departmentName").asText()).isEqualTo("研发部");
    }

    /** 未分配部门时输出 null，兼容构造器仍保持旧字段行为。 */
    @Test
    @DisplayName("未分配部门序列化为 null，兼容构造器不破坏旧响应")
    void serializesNullDepartmentFieldsWhenUnassigned() {
        UserSummaryResponse legacy = new UserSummaryResponse(
                UUID.randomUUID(), "bob", "Bob", null, UserStatus.ACTIVE, List.of("MEMBER"), null, null);
        UserSummaryResponse canonicalNull = new UserSummaryResponse(
                UUID.randomUUID(),
                "carol",
                "Carol",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.LOCAL,
                null,
                null,
                null,
                null,
                null);

        JsonNode legacyJson = objectMapper.readTree(objectMapper.writeValueAsString(legacy));
        assertThat(legacyJson.has("departmentId")).isTrue();
        assertThat(legacyJson.path("departmentId").isNull()).isTrue();
        assertThat(legacyJson.has("departmentName")).isTrue();
        assertThat(legacyJson.path("departmentName").isNull()).isTrue();
        // 兼容构造器仍省略 authSource/identityProvider，不把未知来源误报为 LOCAL。
        assertThat(legacyJson.has("authSource")).isFalse();
        assertThat(legacyJson.has("identityProvider")).isFalse();

        JsonNode canonicalJson = objectMapper.readTree(objectMapper.writeValueAsString(canonicalNull));
        assertThat(canonicalJson.path("departmentId").isNull()).isTrue();
        assertThat(canonicalJson.path("departmentName").isNull()).isTrue();
    }

    /**
     * 读取 OpenAPI {@code UserSummary} schema。
     *
     * @return schema 映射
     */
    private static Map<String, Object> openApiUserSummary() {
        return openApiSchema("UserSummary");
    }

    /**
     * 读取指定 OpenAPI schema。
     *
     * @param name schema 名
     * @return schema 映射
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> openApiSchema(String name) {
        Path contract = locateRepositoryRoot().resolve("contracts").resolve("openapi.yaml");
        try (Reader reader = Files.newBufferedReader(contract, StandardCharsets.UTF_8)) {
            Map<String, Object> document = new Yaml().load(reader);
            Map<String, Object> components = (Map<String, Object>) document.get("components");
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
            assertThat(schema).as("OpenAPI %s", name).isNotEmpty();
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + contract, exception);
        }
    }

    /**
     * 读取 schema 属性。
     *
     * @param schema schema
     * @param name 属性名
     * @return 属性映射
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> property = (Map<String, Object>) properties.get(name);
        assertThat(property).as("property %s", name).isNotNull();
        return property;
    }

    /**
     * 读取 schema 的 required 列表；缺失时按空列表处理（OpenAPI 允许省略 required）。
     *
     * @param schema schema
     * @return required 字段名
     */
    @SuppressWarnings("unchecked")
    private static List<Object> requiredFields(Map<String, Object> schema) {
        Object required = schema.get("required");
        return required instanceof List<?> values ? (List<Object>) values : List.of();
    }

    /**
     * 从当前工作目录向上定位仓库根。
     *
     * @return 含 {@code contracts/openapi.yaml} 的目录
     */
    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("contracts").resolve("openapi.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("contracts/openapi.yaml not found from " + System.getProperty("user.dir"));
    }
}
