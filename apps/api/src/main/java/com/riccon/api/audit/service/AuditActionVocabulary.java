package com.riccon.api.audit.service;

import com.riccon.api.audit.domain.AdditionalAuditActions;
import com.riccon.platform.domain.audit.AuditActions;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 已知审计动作词汇。
 *
 * <p>策略端点需要返回 API 与 Worker 的完整动作列表（ADR-0020 第 1 节），但动作常量由 B1 独占的
 * {@link AuditActions} 与 {@link AdditionalAuditActions} 维护；B3b 不复制字符串，而是反射读取两处
 * {@code public static final String} 常量并按字典序去重。新增动作不需要改动查询/导出代码，避免词汇漂移。
 */
public final class AuditActionVocabulary {

    /** 工具类禁止实例化。 */
    private AuditActionVocabulary() {}

    /**
     * 返回排序后的已知动作名。
     *
     * @return 不可变动作列表
     */
    public static List<String> knownActions() {
        Set<String> actions = new TreeSet<>();
        collect(AuditActions.class, actions);
        collect(AdditionalAuditActions.class, actions);
        return List.copyOf(actions);
    }

    /**
     * 收集一个常量类中的动作名。
     *
     * @param type 常量类
     * @param target 目标集合
     */
    private static void collect(Class<?> type, Set<String> target) {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String text && !text.isBlank()) {
                    target.add(text);
                }
            } catch (IllegalAccessException exception) {
                // 常量都是 public；不可访问属于类结构被改坏，必须让策略端点失败而不是返回缺项。
                throw new IllegalStateException("failed to read audit action constant", exception);
            }
        }
    }
}
