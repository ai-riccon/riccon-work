package com.riccon.api.knowledge.connector.dto;

import com.riccon.api.knowledge.connector.domain.ConnectorTestCategory;
import com.riccon.api.knowledge.connector.domain.ConnectorTestOutcome;
import java.time.Instant;

/**
 * 连接测试响应（OpenAPI {@code ConnectorTestResult}）。
 *
 * <p>失败仍返回 200，{@code success=false} 且给出中文分类原因；不含凭据、端点 URL、bucket 全名或对象 key。
 *
 * @param success 是否成功
 * @param category 失败分类；成功为 null
 * @param message 中文原因
 * @param testedAt 测试时间
 */
public record ConnectorTestResponse(boolean success, ConnectorTestCategory category, String message, Instant testedAt) {

    /**
     * 映射测试结果。
     *
     * @param outcome 内部结果
     * @param testedAt 测试时间
     * @return 响应
     */
    public static ConnectorTestResponse from(ConnectorTestOutcome outcome, Instant testedAt) {
        return new ConnectorTestResponse(outcome.success(), outcome.category(), outcome.message(), testedAt);
    }
}
