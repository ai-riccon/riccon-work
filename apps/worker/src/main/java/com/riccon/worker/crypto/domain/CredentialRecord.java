package com.riccon.worker.crypto.domain;

import java.util.UUID;

/**
 * 供应商凭据记录；只保存密文与 key 引用，不保存明文。
 *
 * @param id 凭据记录 ID
 * @param tenantId 凭据所属租户；M02 为 {@code platform}
 * @param providerId 模型供应商 ID
 * @param credentialType 固定 {@code API_KEY}
 * @param ciphertext 密文格式 {@code enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>}
 * @param keyId 使用的 DEK 标识
 * @param masterKeyId 包裹 DEK 的主密钥标识
 * @param version 凭据版本
 */
public record CredentialRecord(
        UUID id,
        String tenantId,
        UUID providerId,
        String credentialType,
        String ciphertext,
        String keyId,
        String masterKeyId,
        int version) {}
