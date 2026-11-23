package com.riccon.api.knowledge.connector.domain;

/**
 * 连接测试结果（内部）。
 *
 * @param success 是否成功
 * @param category 失败分类；成功为 null
 * @param message 中文原因；不含凭据、端点 URL、桶全名或对象 key
 */
public record ConnectorTestOutcome(boolean success, ConnectorTestCategory category, String message) {

    /**
     * 构造成功结果。
     *
     * @return 成功结果
     */
    public static ConnectorTestOutcome succeeded() {
        return new ConnectorTestOutcome(true, null, "连接测试成功，已通过只读鉴权。");
    }

    /**
     * 构造失败结果。
     *
     * @param category 分类
     * @param message 中文原因
     * @return 失败结果
     */
    public static ConnectorTestOutcome failed(ConnectorTestCategory category, String message) {
        return new ConnectorTestOutcome(false, category, message);
    }
}
