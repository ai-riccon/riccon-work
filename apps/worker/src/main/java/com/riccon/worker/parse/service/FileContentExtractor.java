package com.riccon.worker.parse.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 文件内容抽取入口。
 *
 * <p>文件解析与知识库索引共用同一抽取逻辑：每份文档按字节读取一次，再交给对应解析器，避免
 * PDF/Excel/图片在解析与索引阶段重复下载。M05 起图片需要读取像素做 OCR，不再只校验对象存在；
 * 对象 key 的租户前缀与大小上限由 {@link S3FileContentReader} 在读取前统一校验。
 */
@Component
public class FileContentExtractor {

    /** S3 对象读取器。 */
    private final S3FileContentReader fileReader;

    /** 解析器注册表。 */
    private final DocumentParserRegistry parserRegistry;

    /**
     * 创建抽取器。
     *
     * @param fileReader 对象读取器
     * @param parserRegistry 解析器注册表
     */
    public FileContentExtractor(S3FileContentReader fileReader, DocumentParserRegistry parserRegistry) {
        this.fileReader = Objects.requireNonNull(fileReader, "fileReader must not be null");
        this.parserRegistry = Objects.requireNonNull(parserRegistry, "parserRegistry must not be null");
    }

    /**
     * 抽取文件文本段。
     *
     * @param tenantId 当前租户
     * @param file 文件元数据
     * @return 解析结果
     */
    public ParsedContent extract(TenantId tenantId, FileRecord file) {
        // 同一文档只下载一次：OCR/PDF/Office 都按字节解析，解析器不得再次访问对象存储。
        byte[] content = fileReader.read(tenantId, file);
        return parserRegistry.parse(file, content);
    }
}
