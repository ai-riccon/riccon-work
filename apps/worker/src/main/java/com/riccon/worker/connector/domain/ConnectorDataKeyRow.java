package com.riccon.worker.connector.domain;

/**
 * 连接器凭据 DEK 行。
 *
 * @param wrappedDek 被主密钥包裹的 DEK
 * @param wrapNonce 包裹 nonce
 * @param algorithm 算法
 * @param masterKeyId 主密钥标识
 */
public record ConnectorDataKeyRow(byte[] wrappedDek, byte[] wrapNonce, String algorithm, String masterKeyId) {

    /** 防御性复制字节数组。 */
    public ConnectorDataKeyRow {
        wrappedDek = wrappedDek == null ? null : wrappedDek.clone();
        wrapNonce = wrapNonce == null ? null : wrapNonce.clone();
    }
}
