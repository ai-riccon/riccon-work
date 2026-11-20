package com.riccon.api.cost.domain;

import java.util.UUID;

/**
 * 原始成本查询参数；解析与校验在服务层完成。
 *
 * <p>时间与维度使用字符串接收，保证格式/枚举非法统一返回 {@code RICCON-COST-400}，
 * 而不是 Spring 默认的参数类型错误码。
 *
 * @param from 起始日文本
 * @param to 结束日文本
 * @param groupBy 聚合维度文本
 * @param page 页码
 * @param size 每页大小
 * @param userId 明细的用户过滤，可为空
 * @param modelId 明细的模型过滤，可为空
 */
public record CostQuery(
        String from, String to, String groupBy, Integer page, Integer size, UUID userId, UUID modelId) {}
