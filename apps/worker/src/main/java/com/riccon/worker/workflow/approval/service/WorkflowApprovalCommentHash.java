package com.riccon.worker.workflow.approval.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 审批理由的稳定哈希（DEC-0050=B）。
 *
 * <p>审计 {@code workflow.approval.decided} 的 details 只写 {@code commentSha256}，不写明文理由；
 * 审批记录 {@code workflow_approval.decision_comment} 仍保留 ≤500 字符原文，审批详情继续可读。
 *
 * <p>规范化规则与 API/Worker 两侧的决定命令一致：先去除首尾空白（{@link String#strip()}），
 * 再按 UTF-8 编码计算 SHA-256，输出 64 位小写十六进制。规则必须跨 API 与 Worker 保持稳定；
 * 修改算法或规范化方式会破坏审计可核对性，需先更新 ADR/决策，不能只改一侧。
 */
public final class WorkflowApprovalCommentHash {

    /** 工具类禁止实例化。 */
    private WorkflowApprovalCommentHash() {}

    /**
     * 计算审批理由哈希。
     *
     * @param comment 理由；可空
     * @return SHA-256 小写十六进制；空或空白理由返回 {@code null}
     */
    public static String sha256(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 与命令对象一致先 strip，避免同一理由因首尾空白得到不同审计哈希。
            byte[] hash = digest.digest(comment.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 SHA-256；缺失属于运行时配置错误，不能静默降级为写明文。
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
