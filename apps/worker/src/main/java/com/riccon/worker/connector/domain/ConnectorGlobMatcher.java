package com.riccon.worker.connector.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 连接器排除 glob 匹配。
 *
 * <p>支持 {@code *}（不跨 {@code /}）、{@code **}（跨路径）与 {@code ?}（单字符）；其他正则元字符按字面量
 * 处理。glob 只用于排除，不影响删除传播语义。
 */
public final class ConnectorGlobMatcher {

    /** 工具类禁止实例化。 */
    private ConnectorGlobMatcher() {}

    /**
     * 判断对象 key 是否命中任一排除 glob。
     *
     * @param globs 排除 glob
     * @param key 对象 key
     * @return 命中时返回 true
     */
    public static boolean excluded(List<String> globs, String key) {
        if (globs == null || globs.isEmpty() || key == null) {
            return false;
        }
        for (String glob : globs) {
            if (glob != null && !glob.isBlank() && matches(glob, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个 glob 是否匹配。
     *
     * @param glob glob 模式
     * @param key 对象 key
     * @return 命中时返回 true
     */
    public static boolean matches(String glob, String key) {
        return Pattern.compile(toRegex(glob)).matcher(key).matches();
    }

    /**
     * 把 glob 转为锚定正则。
     *
     * @param glob glob
     * @return 正则
     */
    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            switch (current) {
                case '*' -> {
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                        regex.append(".*");
                        index++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(current);
                }
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
