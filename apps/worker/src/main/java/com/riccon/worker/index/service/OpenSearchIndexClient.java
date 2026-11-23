package com.riccon.worker.index.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.config.OpenSearchProperties;
import com.riccon.worker.index.domain.IndexException;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import com.riccon.worker.index.domain.KnowledgeDocumentRecord;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenSearch 租户索引客户端。
 *
 * <p>索引名只由 {@link TenantId} 派生：别名 {@code riccon-kb-{tenantId}} 指向版本化索引
 * {@code riccon-kb-v2-{tenantId}}（由 {@code riccon.worker.opensearch.index-version} 配置）；
 * 调用方不能传入索引名，避免跨租户检索（ADR-0009 第 7 节）。文档同时冗余 {@code tenant_id}
 * 字段，索引隔离与 term 过滤双保险。
 *
 * <p>M05 起支持 v1→v2 灰度：写请求始终落到配置的版本化索引；{@link #ensureIndex} 只在别名
 * 不存在时创建别名，不会把已有别名从 v1 自动切到 v2；重灌完成后由运维脚本调用
 * {@link #switchAlias} 原子切换，回滚时切回 v1（v1 索引保留，运行期检索不依赖 OpenSearch）。
 * 索引文档新增 ACL/解析元数据只用于诊断，授权权威仍是 MySQL（ADR-0023 第 5.5/7.1 节）。
 *
 * <p>使用 JDK HttpClient + Jackson，不引入额外 OpenSearch 客户端；所有请求设置超时，
 * 5xx/连接失败可重试，4xx/映射错误不可重试。
 */
@Component
public class OpenSearchIndexClient {

    /** 删除文档时使用的路径模板。 */
    private static final String DELETE_BY_QUERY_TEMPLATE = "/%s/_delete_by_query?refresh=true&conflicts=proceed";

    /** 批量写入路径。 */
    private static final String BULK_PATH = "/_bulk?refresh=true";

    /** 别名原子切换路径。 */
    private static final String ALIASES_PATH = "/_aliases";

    /** 索引版本格式：v1、v2、v10 等。 */
    private static final String INDEX_VERSION_PATTERN = "v[0-9]+";

    /** HTTP 客户端。 */
    private final HttpClient httpClient;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** OpenSearch 基础 URI，不带尾部斜杠。 */
    private final String baseUrl;

    /** Basic Auth 头；未配置时为空串。 */
    private final String authorization;

    /** 单请求超时。 */
    private final Duration requestTimeout;

    /** 索引名前缀。 */
    private final String indexPrefix;

    /** 索引结构版本。 */
    private final String indexVersion;

    /**
     * 创建索引客户端。
     *
     * @param properties OpenSearch 配置
     * @param objectMapper JSON 序列化器
     */
    public OpenSearchIndexClient(OpenSearchProperties properties, ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.baseUrl = stripTrailingSlash(properties.url());
        this.requestTimeout = properties.requestTimeout();
        this.indexPrefix = properties.indexPrefix();
        this.indexVersion = properties.indexVersion();
        this.authorization = properties.username().isBlank()
                ? ""
                : "Basic "
                        + Base64.getEncoder()
                                .encodeToString((properties.username() + ":" + properties.password())
                                        .getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * 确保租户写入索引与别名存在。
     *
     * <p>目标版本索引不存在时按 v2 mapping 创建；别名已存在（例如仍指向 v1）时保持不变，
     * 由重灌脚本在完成后切换，避免灰度期间读写指向不同版本。
     *
     * @param tenantId 当前租户
     * @param dimensions 向量维度
     */
    public void ensureIndex(TenantId tenantId, int dimensions) {
        String index = indexName(tenantId);
        createIndexIfMissing(index, dimensions);
        String alias = aliasName(tenantId);
        if (!aliasExists(alias)) {
            createAlias(index, alias);
        }
    }

    /**
     * 以文档为单位重建索引：先删除该文档旧文档，再批量写入新分块。
     *
     * <p>重建不会清理其他文档；同一文档重复调用结果一致。ACL_ONLY 模式复用本方法时传入
     * MySQL 现有分块（chunk_id 不变），因此不会替换权威分块。
     *
     * @param tenantId 当前租户
     * @param document 文档快照（标题、ACL 与解析元数据）
     * @param chunks 分块记录
     * @param embeddings 与分块一一对应的向量
     */
    public void replaceDocument(
            TenantId tenantId,
            KnowledgeDocumentRecord document,
            List<KnowledgeChunkRecord> chunks,
            List<float[]> embeddings) {
        Objects.requireNonNull(document, "document must not be null");
        if (chunks.size() != embeddings.size()) {
            throw new IndexException(
                    WorkerErrorCodes.INDEX_REJECTED, false, "embedding count does not match chunk count");
        }
        int dimensions = embeddings.isEmpty() ? 0 : embeddings.get(0).length;
        if (dimensions > 0) {
            ensureIndex(tenantId, dimensions);
        }
        String index = indexName(tenantId);
        deleteDocumentDocuments(index, tenantId, document.id());
        if (chunks.isEmpty()) {
            return;
        }
        bulkIndex(index, tenantId, document, chunks, embeddings);
    }

    /**
     * 兼容 M02–M04 的按标题重建入口。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     * @param title 文档标题
     * @param chunks 分块记录
     * @param embeddings 与分块一一对应的向量
     */
    public void replaceDocument(
            TenantId tenantId,
            UUID documentId,
            String title,
            List<KnowledgeChunkRecord> chunks,
            List<float[]> embeddings) {
        replaceDocument(tenantId, KnowledgeDocumentRecord.minimal(documentId, title), chunks, embeddings);
    }

    /**
     * 删除单个文档在租户所有相关索引（当前版本索引与别名目标）中的文档。
     *
     * <p>使用带 {@code tenant_id} 与 {@code document_id} 双过滤的 {@code _delete_by_query}，
     * 重复调用幂等；跨租户 ID 不会命中其他租户索引。
     *
     * @param tenantId 当前租户
     * @param documentId 文档 ID
     */
    public void deleteDocument(TenantId tenantId, UUID documentId) {
        Set<String> indices = new LinkedHashSet<>();
        indices.add(indexName(tenantId));
        indices.addAll(aliasTargets(tenantId));
        for (String index : indices) {
            if (indexExists(index)) {
                deleteDocumentDocuments(index, tenantId, documentId);
            }
        }
    }

    /**
     * 返回租户索引别名，供测试与诊断使用。
     *
     * @param tenantId 当前租户
     * @return 别名
     */
    public String aliasName(TenantId tenantId) {
        return indexPrefix + "-" + indexSuffix(tenantId);
    }

    /**
     * 返回租户当前配置版本的索引名，供测试与诊断使用。
     *
     * @param tenantId 当前租户
     * @return 索引名
     */
    public String indexName(TenantId tenantId) {
        return indexName(tenantId, indexVersion);
    }

    /**
     * 返回租户指定版本的索引名。
     *
     * @param tenantId 当前租户
     * @param version 索引版本，例如 {@code v1}/{@code v2}
     * @return 索引名
     */
    public String indexName(TenantId tenantId, String version) {
        if (version == null || !version.matches(INDEX_VERSION_PATTERN)) {
            throw new IndexException(WorkerErrorCodes.INDEX_REJECTED, false, "invalid opensearch index version");
        }
        return indexPrefix + "-" + version + "-" + indexSuffix(tenantId);
    }

    /**
     * 返回租户当前配置的索引版本。
     *
     * @return 索引版本
     */
    public String configuredIndexVersion() {
        return indexVersion;
    }

    /**
     * 查询别名当前指向的索引列表。
     *
     * @param tenantId 当前租户
     * @return 目标索引；别名不存在时为空列表
     */
    public List<String> aliasTargets(TenantId tenantId) {
        String alias = aliasName(tenantId);
        HttpRequest request =
                requestBuilder(URI.create(baseUrl + "/_alias/" + alias)).GET().build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return List.of();
        }
        requireSuccess(response, "alias lookup");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            List<String> targets = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                targets.add(entry.getKey());
            }
            return List.copyOf(targets);
        } catch (JacksonException ex) {
            throw IndexException.unavailable("opensearch alias response is not valid JSON", ex);
        }
    }

    /**
     * 把租户别名原子切换到目标版本索引。
     *
     * <p>一次 {@code POST /_aliases} 完成“移除旧目标 + 添加新目标”，避免别名短暂无目标；
     * 目标索引必须已存在（重灌后由 Worker 创建），切换失败抛不可重试错误，调用方不得切换
     * 未就绪的索引。回切 v1 使用同一方法。
     *
     * @param tenantId 当前租户
     * @param targetVersion 目标版本，例如 {@code v2}
     * @return 切换前的索引目标列表
     */
    public List<String> switchAlias(TenantId tenantId, String targetVersion) {
        String alias = aliasName(tenantId);
        String target = indexName(tenantId, targetVersion);
        if (!indexExists(target)) {
            throw new IndexException(
                    WorkerErrorCodes.INDEX_REJECTED, false, "alias target index does not exist: " + targetVersion);
        }
        List<String> previous = aliasTargets(tenantId);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String index : previous) {
            if (!index.equals(target)) {
                actions.add(Map.of("remove", Map.of("index", index, "alias", alias)));
            }
        }
        if (!previous.contains(target)) {
            actions.add(Map.of("add", Map.of("index", target, "alias", alias)));
        }
        if (actions.isEmpty()) {
            return previous;
        }
        HttpRequest request = requestBuilder(URI.create(baseUrl + ALIASES_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("actions", actions)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "switch alias");
        return previous;
    }

    /**
     * 删除单个文档的旧索引文档。
     *
     * @param index 索引名
     * @param tenantId 当前租户（用于 term 过滤，防止跨租户误删）
     * @param documentId 文档 ID
     */
    private void deleteDocumentDocuments(String index, TenantId tenantId, UUID documentId) {
        Map<String, Object> body = Map.of(
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("tenant_id", tenantId.value())),
                                        Map.of("term", Map.of("document_id", documentId.toString()))))));
        HttpRequest request = requestBuilder(URI.create(baseUrl + DELETE_BY_QUERY_TEMPLATE.formatted(index)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "delete by query");
    }

    /**
     * 批量写入分块文档。
     *
     * @param index 索引名
     * @param tenantId 当前租户
     * @param document 文档快照
     * @param chunks 分块
     * @param embeddings 向量
     */
    private void bulkIndex(
            String index,
            TenantId tenantId,
            KnowledgeDocumentRecord document,
            List<KnowledgeChunkRecord> chunks,
            List<float[]> embeddings) {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkRecord chunk = chunks.get(i);
            Map<String, Object> action =
                    Map.of("index", Map.of("_index", index, "_id", chunk.id().toString()));
            payload.append(json(action)).append('\n');
            payload.append(json(document(tenantId, document, chunk, embeddings.get(i))))
                    .append('\n');
        }
        HttpRequest request = requestBuilder(URI.create(baseUrl + BULK_PATH))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "bulk index");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("errors").asBoolean(false)) {
                throw IndexException.unavailable("opensearch bulk indexing reported item errors", null);
            }
        } catch (JacksonException ex) {
            throw IndexException.unavailable("opensearch bulk response is not valid JSON", ex);
        }
    }

    /**
     * 构造索引文档；定位/ACL/解析字段为 null 时不写入，检索侧按缺失处理。
     *
     * @param tenantId 当前租户
     * @param document 文档快照
     * @param chunk 分块
     * @param embedding 向量
     * @return 文档字段
     */
    private Map<String, Object> document(
            TenantId tenantId, KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk, float[] embedding) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tenant_id", tenantId.value());
        doc.put("document_id", document.id().toString());
        doc.put("chunk_id", chunk.id().toString());
        doc.put("title", document.title());
        doc.put("content", chunk.content());
        doc.put("locator_type", chunk.locatorType().name());
        doc.put("token_count", chunk.tokenCount());
        doc.put("embedding", embedding);
        doc.put("visibility", document.visibility().name());
        doc.put("acl_version", document.aclVersion());
        doc.put("source_type", document.sourceType());
        putIfPresent(
                doc,
                "department_id",
                document.departmentId() == null ? null : document.departmentId().toString());
        putIfPresent(
                doc,
                "connector_id",
                document.connectorId() == null ? null : document.connectorId().toString());
        putIfPresent(
                doc,
                "parse_engine",
                document.parseEngine() == null ? null : document.parseEngine().name());
        putIfPresent(doc, "page_no", chunk.pageNo());
        putIfPresent(doc, "sheet_name", chunk.sheetName());
        putIfPresent(doc, "cell_range", chunk.cellRange());
        putIfPresent(doc, "paragraph_no", chunk.paragraphNo());
        if (chunk.bbox() != null) {
            doc.put("bbox_x", chunk.bbox().x());
            doc.put("bbox_y", chunk.bbox().y());
            doc.put("bbox_w", chunk.bbox().width());
            doc.put("bbox_h", chunk.bbox().height());
        }
        putIfPresent(doc, "ocr_confidence", chunk.ocrConfidence());
        return doc;
    }

    /**
     * 检查别名是否存在。
     *
     * @param alias 别名
     * @return 是否存在
     */
    private boolean aliasExists(String alias) {
        HttpRequest request = requestBuilder(URI.create(baseUrl + "/_alias/" + alias))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw translate(response.statusCode(), "alias check");
    }

    /**
     * 检查索引是否存在。
     *
     * @param index 索引名
     * @return 是否存在
     */
    private boolean indexExists(String index) {
        HttpRequest request = requestBuilder(URI.create(baseUrl + "/" + index))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw translate(response.statusCode(), "index check");
    }

    /**
     * 创建索引（已存在时忽略）。
     *
     * @param index 索引名
     * @param dimensions 向量维度
     */
    private void createIndexIfMissing(String index, int dimensions) {
        Map<String, Object> mapping = indexMapping(dimensions);
        HttpRequest request = requestBuilder(URI.create(baseUrl + "/" + index))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(mapping), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            return;
        }
        if (response.statusCode() == 400 && response.body().contains("resource_already_exists_exception")) {
            return;
        }
        requireSuccess(response, "create index");
    }

    /**
     * 创建别名并确认生效。
     *
     * @param index 索引名
     * @param alias 别名
     */
    private void createAlias(String index, String alias) {
        Map<String, Object> body = Map.of("actions", List.of(Map.of("add", Map.of("index", index, "alias", alias))));
        HttpRequest request = requestBuilder(URI.create(baseUrl + ALIASES_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 200 || response.statusCode() == 201) || aliasExists(alias)) {
            return;
        }
        requireSuccess(response, "create alias");
    }

    /**
     * 构造索引设置与映射；向量使用 OpenSearch knn_vector。
     *
     * <p>{@code dynamic: strict}，所有 M05 字段必须随 mapping 一起创建：ACL/来源诊断字段
     * （visibility/department_id/acl_version/source_type/connector_id）、解析引擎与 OCR
     * 区域/置信度（parse_engine/bbox_x/y/w/h/ocr_confidence）。{@code sourceStatus} 不入索引：
     * 引用可用性按请求从 MySQL 当前 ACL/删除/版本计算，写入索引会变成过期快照。
     *
     * @param dimensions 向量维度
     * @return 索引定义
     */
    private static Map<String, Object> indexMapping(int dimensions) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenant_id", Map.of("type", "keyword"));
        properties.put("document_id", Map.of("type", "keyword"));
        properties.put("chunk_id", Map.of("type", "keyword"));
        properties.put(
                "title",
                Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword", "ignore_above", 300))));
        properties.put("content", Map.of("type", "text"));
        properties.put("locator_type", Map.of("type", "keyword"));
        properties.put("page_no", Map.of("type", "integer"));
        properties.put("sheet_name", Map.of("type", "keyword"));
        properties.put("cell_range", Map.of("type", "keyword"));
        properties.put("paragraph_no", Map.of("type", "integer"));
        properties.put("token_count", Map.of("type", "integer"));
        properties.put("visibility", Map.of("type", "keyword"));
        properties.put("department_id", Map.of("type", "keyword"));
        properties.put("acl_version", Map.of("type", "integer"));
        properties.put("source_type", Map.of("type", "keyword"));
        properties.put("connector_id", Map.of("type", "keyword"));
        properties.put("parse_engine", Map.of("type", "keyword"));
        properties.put("ocr_confidence", Map.of("type", "float"));
        properties.put("bbox_x", Map.of("type", "integer"));
        properties.put("bbox_y", Map.of("type", "integer"));
        properties.put("bbox_w", Map.of("type", "integer"));
        properties.put("bbox_h", Map.of("type", "integer"));
        properties.put(
                "embedding",
                Map.of(
                        "type",
                        "knn_vector",
                        "dimension",
                        dimensions,
                        "method",
                        Map.of(
                                "name", "hnsw",
                                "space_type", "cosinesimil",
                                "engine", "lucene",
                                "parameters", Map.of())));
        return Map.of(
                "settings",
                        Map.of(
                                "number_of_shards", 1,
                                "number_of_replicas", 0,
                                "index", Map.of("knn", true)),
                "mappings", Map.of("dynamic", "strict", "properties", properties));
    }

    /**
     * 构造带超时与可选认证的请求构建器。
     *
     * @param uri 请求 URI
     * @return 请求构建器
     */
    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        if (!authorization.isEmpty()) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    /**
     * 发送请求并翻译 IO 异常。
     *
     * @param request HTTP 请求
     * @param handler 响应处理器
     * @param <T> 响应类型
     * @return 响应
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw IndexException.unavailable("opensearch request was interrupted", ex);
        } catch (IOException ex) {
            throw IndexException.unavailable("opensearch is unavailable", ex);
        }
    }

    /**
     * 校验 2xx 响应，否则按状态码翻译。
     *
     * @param response HTTP 响应
     * @param operation 操作名，仅用于错误摘要
     */
    private static void requireSuccess(HttpResponse<String> response, String operation) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw translate(status, operation);
    }

    /**
     * 将 HTTP 状态翻译为可重试/不可重试索引错误。
     *
     * @param status 状态码
     * @param operation 操作名
     * @return 索引异常
     */
    private static IndexException translate(int status, String operation) {
        if (status >= 500) {
            return IndexException.unavailable("opensearch " + operation + " failed with status " + status, null);
        }
        return IndexException.rejected("opensearch " + operation + " rejected with status " + status);
    }

    /**
     * 序列化 JSON；失败说明对象不可序列化，属于编码错误。
     *
     * @param value 待序列化对象
     * @return JSON 字符串
     */
    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /**
     * 去除 URL 尾部斜杠。
     *
     * @param url 原始 URL
     * @return 规范 URL
     */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 派生索引后缀；OpenSearch 索引名必须小写，平台租户代码统一使用小写。
     *
     * @param tenantId 当前租户
     * @return 小写租户后缀
     */
    private static String indexSuffix(TenantId tenantId) {
        String value = tenantId.value().toLowerCase(Locale.ROOT);
        if (!tenantId.value().equals(value)) {
            // 大小写归一化会让 TenantA/tenanta 共用一个索引，属于跨租户泄漏风险；宁可拒绝。
            throw new IndexException(
                    WorkerErrorCodes.INDEX_REJECTED, false, "tenant id must be lowercase for opensearch index naming");
        }
        if (!value.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IndexException(
                    WorkerErrorCodes.INDEX_REJECTED, false, "tenant id is not a valid opensearch index suffix");
        }
        return value;
    }

    /**
     * 仅在值存在时写入映射。
     *
     * @param doc 文档字段
     * @param key 字段名
     * @param value 值
     */
    private static void putIfPresent(Map<String, Object> doc, String key, Object value) {
        if (value != null) {
            doc.put(key, value);
        }
    }
}
