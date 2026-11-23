package com.riccon.worker.ocr.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 镜像内 OCR 指纹 manifest（ADR-0023 第 4.6 节）。
 *
 * <p>O1a 在 {@code /opt/riccon/ocr/manifest.json} 写入 Tesseract/Leptonica 版本与语言包
 * SHA-256；本类只读取该文件并派生一个稳定的 {@code model_hash} 供 {@code /actuator/info}
 * 暴露。不记录 tessdata 路径、对象 key 或任何密钥；文件缺失或损坏时降级为 {@code unknown}，
 * 不阻断 Worker 启动（语言可用性由引擎自检负责）。
 *
 * @param engine 引擎标识
 * @param engineVersion Tesseract 版本
 * @param leptonicaVersion Leptonica 版本
 * @param tessdata 权重来源标识（例如 tessdata_fast@commit）
 * @param languageHashes 语言到文件 SHA-256 的映射
 */
public record OcrManifest(
        String engine,
        String engineVersion,
        String leptonicaVersion,
        String tessdata,
        Map<String, String> languageHashes) {

    /** 日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(OcrManifest.class);

    /** 指纹缺失时的稳定值。 */
    public static final String UNKNOWN = "unknown";

    /** 防御性复制语言哈希。 */
    public OcrManifest {
        languageHashes = languageHashes == null ? Map.of() : Map.copyOf(languageHashes);
    }

    /**
     * 读取 manifest；不存在或不可解析时返回空指纹。
     *
     * @param path manifest 路径
     * @return 指纹；缺失时为空指纹
     */
    public static OcrManifest load(String path) {
        if (path == null || path.isBlank()) {
            return empty();
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> hashes = new TreeMap<>();
            JsonNode languages = root.path("languages");
            if (languages.isObject()) {
                languages.propertyNames().forEach(name -> {
                    JsonNode value = languages.get(name);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        hashes.put(name, value.asText());
                    }
                });
            }
            return new OcrManifest(
                    text(root, "engine"),
                    text(root, "engineVersion"),
                    text(root, "leptonicaVersion"),
                    text(root, "tessdata"),
                    hashes);
        } catch (RuntimeException | java.io.IOException exception) {
            // 指纹只用于观测；损坏时降级，不影响 OCR 主路径。
            LOG.warn(
                    "ocr.manifest.unreadable path={} reason={}",
                    path,
                    exception.getClass().getSimpleName());
            return empty();
        }
    }

    /**
     * 返回空指纹。
     *
     * @return engine 为 tesseract、版本与哈希未知的指纹
     */
    public static OcrManifest empty() {
        return new OcrManifest("tesseract", UNKNOWN, UNKNOWN, UNKNOWN, Map.of());
    }

    /**
     * 返回稳定的模型指纹。
     *
     * <p>对语言名与文件哈希排序后取 SHA-256；同一权重集合在不同进程/镜像中稳定，不包含路径。
     *
     * @return 64 位十六进制哈希；无语言哈希时为 {@link #UNKNOWN}
     */
    public String modelHash() {
        if (languageHashes.isEmpty()) {
            return UNKNOWN;
        }
        String canonical = languageHashes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(";"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 SHA-256；如果缺失，返回 unknown 而不是把异常带到启动路径。
            LOG.warn(
                    "ocr.manifest.sha256_unavailable reason={}",
                    exception.getClass().getSimpleName());
            return UNKNOWN;
        }
    }

    /**
     * 读取可空文本字段。
     *
     * @param root JSON 根节点
     * @param field 字段名
     * @return 文本或 {@link #UNKNOWN}
     */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : UNKNOWN;
    }
}
