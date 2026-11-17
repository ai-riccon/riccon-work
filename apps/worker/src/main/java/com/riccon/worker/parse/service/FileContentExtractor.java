package com.riccon.worker.parse.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 文件内容抽取入口。
 *
 * <p>文件解析与知识库索引共用同一抽取逻辑：普通文档下载后交给解析器；图片只校验对象存在，
 * 由图片解析器输出元数据，不读取像素内容（M02 不做 OCR）。
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
        if (file.format() == FileFormat.PNG || file.format() == FileFormat.JPEG) {
            fileReader.verifyExists(tenantId, file);
            return parserRegistry.parse(file, new byte[0]);
        }
        byte[] content = fileReader.read(tenantId, file);
        return parserRegistry.parse(file, content);
    }
}
