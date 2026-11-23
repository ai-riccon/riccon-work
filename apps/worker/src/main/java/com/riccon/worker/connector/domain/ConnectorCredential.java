package com.riccon.worker.connector.domain;

import java.util.Arrays;

/**
 * 解密后的连接器凭据；使用后必须关闭以清零字符数组。
 */
public final class ConnectorCredential implements AutoCloseable {

    /** 访问键字符。 */
    private final char[] accessKeyId;

    /** 访问密钥字符。 */
    private final char[] secretAccessKey;

    /**
     * 创建凭据。
     *
     * @param accessKeyId 访问键
     * @param secretAccessKey 访问密钥
     */
    public ConnectorCredential(char[] accessKeyId, char[] secretAccessKey) {
        this.accessKeyId = accessKeyId.clone();
        this.secretAccessKey = secretAccessKey.clone();
        Arrays.fill(accessKeyId, '\0');
        Arrays.fill(secretAccessKey, '\0');
    }

    /**
     * 返回访问键副本。
     *
     * @return 访问键
     */
    public char[] accessKeyId() {
        return accessKeyId.clone();
    }

    /**
     * 返回访问密钥副本。
     *
     * @return 访问密钥
     */
    public char[] secretAccessKey() {
        return secretAccessKey.clone();
    }

    /** 清零内部字符数组。 */
    @Override
    public void close() {
        Arrays.fill(accessKeyId, '\0');
        Arrays.fill(secretAccessKey, '\0');
    }
}
