package com.riccon.worker.research.domain;

import java.util.List;

/**
 * 已组装完成的 Markdown 报告。
 *
 * <p>正文包含标题、摘要、分节正文、引用清单与数据来源；引用清单与 {@link #citations} 序号一一对应，
 * 用于写入 {@code artifact_citation}。
 *
 * @param title 产物标题
 * @param body Markdown 正文
 * @param summary 摘要，用于产物版本列表展示
 * @param citations 引用清单
 */
public record ReportDocument(String title, String body, String summary, List<ReportCitation> citations) {

    /** 校验必填字段并复制引用清单。 */
    public ReportDocument {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("report title must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("report body must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("report summary must not be blank");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
