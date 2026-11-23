package com.riccon.api.knowledge.connector.domain;

/**
 * 连接器 {@code externalId} 展示规则。
 *
 * <p>完整源对象 key 只用于库内比对与排障；API/UI 只展示最后两个路径段，若仍超过 32 字符则取最后 32 字符
 * （ADR-0023 第 6.5 节、OpenAPI {@code ConnectorItem.externalId}）。
 */
public final class ConnectorExternalIds {

    /** 展示值最大长度。 */
    private static final int MAX_DISPLAY_LENGTH = 32;

    /** 工具类禁止实例化。 */
    private ConnectorExternalIds() {}

    /**
     * 生成展示值。
     *
     * @param externalId 完整 key；null 返回 null
     * @return 末两段路径或最后 32 字符
     */
    public static String display(String externalId) {
        if (externalId == null) {
            return null;
        }
        if (externalId.isBlank()) {
            return "";
        }
        String value = externalId;
        String[] segments = externalId.split("/");
        if (segments.length >= 2) {
            value = segments[segments.length - 2] + "/" + segments[segments.length - 1];
        }
        if (value.length() > MAX_DISPLAY_LENGTH) {
            value = value.substring(value.length() - MAX_DISPLAY_LENGTH);
        }
        return value;
    }
}
