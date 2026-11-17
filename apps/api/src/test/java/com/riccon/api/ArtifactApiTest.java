package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 产物 API 集成测试。
 *
 * <p>覆盖列表隔离、详情与当前版本、版本分页与引用清单、软删除不可见、跨租户 404 与下载地址 TTL/审计。
 * 产物由 Worker 写入，测试用 SQL 落库模拟；下载只验证预签名 URL，不发起对象存储请求。
 */
class ArtifactApiTest extends ResearchApiTestSupport {

    /** 列表只返回本工作空间未删除产物。 */
    @Test
    @DisplayName("产物列表按工作空间隔离")
    void list_filtersByWorkspaceAndExcludesDeleted() {
        Fixture fixture = fixture();
        UUID visible = seedArtifact(
                fixture.tenant(), fixture.adminWorkspace(), null, userId(fixture.admin()), "可见报告", "SAVED", 1);
        seedArtifact(fixture.tenant(), fixture.adminWorkspace(), null, userId(fixture.admin()), "已删除报告", "DELETED", 1);
        seedArtifact(fixture.tenant(), fixture.memberWorkspace(), null, userId(fixture.member()), "他人空间报告", "SAVED", 1);

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/workspaces/" + fixture.adminWorkspace() + "/artifacts?page=1&size=20",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode page = client.json(response);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(visible.toString());
        assertThat(page.path("items").get(0).path("kind").asText()).isEqualTo("REPORT");
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("SAVED");
        assertThat(page.path("items").get(0).path("currentVersion").asInt()).isEqualTo(1);

        ApiTestClient.ApiResponse invisible = client.get(
                "/api/v1/workspaces/" + fixture.adminWorkspace() + "/artifacts",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(invisible.status()).isEqualTo(404);
    }

    /** 详情返回当前版本与引用清单；软删除与跨租户都 404。 */
    @Test
    @DisplayName("产物详情与引用")
    void get_returnsCurrentVersionAndCitations() {
        Fixture fixture = fixture();
        Fixture other = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.admin(), fixture.adminWorkspace(), "产物引用主题", toolId, "art-get-01");
        UUID invocationId = seedInvocation(
                fixture.tenant(), task.path("id").asText(), runId(task), null, toolId, "search_market", "SUCCEEDED");
        UUID artifactId = seedArtifact(
                fixture.tenant(),
                fixture.adminWorkspace(),
                task.path("id").asText(),
                userId(fixture.admin()),
                "季度市场报告",
                "SAVED",
                1);
        UUID versionId = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.admin()), "摘要");
        seedCitation(fixture.tenant(), versionId, 1, invocationId, "来源标题", "来源片段");

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/artifacts/" + artifactId, fixture.admin().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode detail = client.json(response);
        assertThat(detail.path("id").asText()).isEqualTo(artifactId.toString());
        assertThat(detail.path("researchTaskId").asText())
                .isEqualTo(task.path("id").asText());
        assertThat(detail.path("title").asText()).isEqualTo("季度市场报告");
        assertThat(detail.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(detail.path("currentVersionDetail").path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("currentVersionDetail").path("sizeBytes").asLong())
                .isEqualTo(128);
        assertThat(detail.path("currentVersionDetail").path("citations")).hasSize(1);
        assertThat(detail.path("currentVersionDetail")
                        .path("citations")
                        .get(0)
                        .path("title")
                        .asText())
                .isEqualTo("来源标题");
        assertThat(response.body()).doesNotContain("storageKey").doesNotContain("tenants/");

        UUID deleted = seedArtifact(
                fixture.tenant(), fixture.adminWorkspace(), null, userId(fixture.admin()), "已删除", "DELETED", 1);
        assertThat(client.get("/api/v1/artifacts/" + deleted, fixture.admin().sessionCookie(), Map.of())
                        .status())
                .isEqualTo(404);
        assertThat(client.get("/api/v1/artifacts/" + artifactId, other.admin().sessionCookie(), Map.of())
                        .status())
                .isEqualTo(404);
    }

    /** 版本分页按版本倒序，单版本返回引用；非法版本号与不存在版本分别 400/404。 */
    @Test
    @DisplayName("版本列表与单版本读取")
    void versions_listAndReadSingle() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.admin(), fixture.adminWorkspace(), "版本列表主题", toolId, "art-ver-01");
        UUID invocationId = seedInvocation(
                fixture.tenant(), task.path("id").asText(), runId(task), null, toolId, "search_market", "SUCCEEDED");
        UUID artifactId = seedArtifact(
                fixture.tenant(),
                fixture.adminWorkspace(),
                task.path("id").asText(),
                userId(fixture.admin()),
                "多版本报告",
                "SAVED",
                2);
        UUID firstVersion = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.admin()), "第一版");
        UUID secondVersion = seedArtifactVersion(fixture.tenant(), artifactId, 2, userId(fixture.admin()), "第二版");
        seedCitation(fixture.tenant(), secondVersion, 1, invocationId, "第二版来源", "第二版片段");

        ApiTestClient.ApiResponse list = client.get(
                "/api/v1/artifacts/" + artifactId + "/versions?page=1&size=20",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        JsonNode page = client.json(list);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("citations")).hasSize(1);
        assertThat(page.path("items").get(1).path("version").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(1).path("id").asText()).isEqualTo(firstVersion.toString());

        ApiTestClient.ApiResponse single = client.get(
                "/api/v1/artifacts/" + artifactId + "/versions/1",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(single.status()).as(single.body()).isEqualTo(200);
        assertThat(client.json(single).path("summary").asText()).isEqualTo("第一版");

        assertThat(client.get(
                                "/api/v1/artifacts/" + artifactId + "/versions/9",
                                fixture.admin().sessionCookie(),
                                Map.of())
                        .status())
                .isEqualTo(404);
        assertThat(client.get(
                                "/api/v1/artifacts/" + artifactId + "/versions/0",
                                fixture.admin().sessionCookie(),
                                Map.of())
                        .status())
                .isEqualTo(400);
    }

    /** 下载地址为短期预签名 URL，并写 artifact.downloaded 审计。 */
    @Test
    @DisplayName("下载地址与审计")
    void downloadUrl_returnsPresignedUrlAndAudits() {
        Fixture fixture = fixture();
        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.adminWorkspace(), null, userId(fixture.admin()), "可下载报告", "SAVED", 1);
        seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.admin()), "摘要");

        Instant before = Instant.now();
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/artifacts/" + artifactId + "/versions/1/download-url",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        String url = body.path("url").asText();
        assertThat(url).startsWith("http").contains("v1.json");
        Instant expiresAt = Instant.parse(body.path("expiresAt").asText());
        assertThat(expiresAt).isAfter(before);
        assertThat(expiresAt).isBefore(before.plusSeconds(301));

        assertThat(auditRows("artifact.downloaded"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.targetId()).isEqualTo(artifactId.toString());
                    try {
                        JsonNode details = TEST_JSON.readTree(row.details());
                        assertThat(details.path("artifactId").asText()).isEqualTo(artifactId.toString());
                        assertThat(details.path("version").asInt()).isEqualTo(1);
                    } catch (tools.jackson.core.JacksonException exception) {
                        throw new AssertionError("audit details is not valid JSON: " + row.details(), exception);
                    }
                });
    }

    /** 没有正文的产物详情返回 null 当前版本。 */
    @Test
    @DisplayName("空产物版本返回 null")
    void get_withoutVersion_returnsNullDetail() {
        Fixture fixture = fixture();
        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.adminWorkspace(), null, userId(fixture.admin()), "空白报告", "DRAFT", 0);
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/artifacts/" + artifactId, fixture.admin().sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("currentVersionDetail").isNull()).isTrue();
    }
}
