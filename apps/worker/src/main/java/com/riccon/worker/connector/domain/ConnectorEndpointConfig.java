package com.riccon.worker.connector.domain;

/**
 * 部署允许列表中的原始端点条目（{@code RICCON_CONNECTOR_ENDPOINTS}）。
 *
 * <p>连接器只能引用这里的 {@code id}，不能提交任意 URL；URL、HTTP 例外与生产标记都由部署配置决定。
 * 字段与 RUNBOOK「M05b-1 端点允许列表」一致，解析后由 {@link ConnectorEndpointPolicy} 统一校验。
 *
 * @param id 端点标识，连接器只保存该值
 * @param url S3 兼容端点 URL
 * @param label 展示标签，写入日志时也不得包含用户名或凭据
 * @param allowHttp 是否允许明文 HTTP；仅内部端点显式开启
 * @param production 是否生产端点；生产端点不允许 HTTP
 */
public record ConnectorEndpointConfig(String id, String url, String label, boolean allowHttp, boolean production) {}
