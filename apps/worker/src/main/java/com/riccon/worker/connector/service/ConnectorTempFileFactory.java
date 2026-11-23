package com.riccon.worker.connector.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 连接器下载临时文件工厂。
 *
 * <p>独立 bean 便于测试替换路径并断言失败时清理；生产实现使用系统临时目录，文件由同步流程在 finally/异常
 * 分支删除。
 */
@Component
public class ConnectorTempFileFactory {

    /**
     * 创建临时文件。
     *
     * @return 临时文件路径
     * @throws IOException 临时目录不可写
     */
    public Path create() throws IOException {
        return Files.createTempFile("riccon-connector-", ".tmp");
    }
}
