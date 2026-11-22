package com.riccon.worker.model.domain;

import java.util.UUID;

/**
 * 平台托管 key 池中的一把凭据（只含密文与展示级元数据）。
 *
 * <p>和 {@code CredentialRecord} 的区别：本记录额外携带 `priority` 与 `masked_hint`，用于池排序
 * 和审计；明文只在构造 `Authorization` 头时短暂存在，不进入本记录。
 *
 * @param id 凭据 ID
 * @param providerId 供应商 ID
 * @param ciphertext 信封密文
 * @param keyId DEK 标识
 * @param masterKeyId 包裹 DEK 的主密钥标识
 * @param version 凭据版本
 * @param maskedHint 仅末 4 位的掩码
 * @param priority 池内优先级，越小越先使用
 */
public record ModelCredential(
        UUID id,
        UUID providerId,
        String ciphertext,
        String keyId,
        String masterKeyId,
        int version,
        String maskedHint,
        int priority) {}
