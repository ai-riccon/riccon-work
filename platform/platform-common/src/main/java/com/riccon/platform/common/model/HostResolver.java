package com.riccon.platform.common.model;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 主机名解析接缝。
 *
 * <p>生产实现使用 {@link InetAddress#getAllByName(String)}；测试注入固定映射，避免依赖外部 DNS。
 * 解析只用于拒绝元数据/链路本地/回环地址，不做“解析后固定 IP”，DNS rebinding 的残余风险由 TLS 主机名校验、
 * 禁止重定向与部署出网策略共同约束（ADR-0021 §2.2、威胁模型 R-23）。
 */
@FunctionalInterface
public interface HostResolver {

    /**
     * 解析主机名。
     *
     * @param host 主机名或 IP 字面量
     * @return 解析结果
     * @throws UnknownHostException 主机名无法解析
     */
    InetAddress[] resolve(String host) throws UnknownHostException;

    /**
     * 返回生产解析器，使用 JDK 默认 DNS。
     *
     * @return 基于 {@link InetAddress#getAllByName(String)} 的解析器
     */
    static HostResolver system() {
        return InetAddress::getAllByName;
    }
}
