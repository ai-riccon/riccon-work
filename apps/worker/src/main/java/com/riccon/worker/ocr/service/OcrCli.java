package com.riccon.worker.ocr.service;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrManifest;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OCR standalone 入口（ADR-0023 第 4.7 节 SEC-KB-001）。
 *
 * <p>只依赖 {@code OcrEngine}+图片路径，不启动 Spring、MySQL、OpenSearch 或对象存储；用于
 * {@code --network none} 容器内的零出网 OCR 冒烟（O1b {@code scripts/ops/ocr-check}）。
 * 输出每行文本与平均置信度，失败时输出稳定错误码并以非零码退出。
 *
 * <p>用法：{@code java -cp app.jar com.riccon.worker.ocr.service.OcrCli <image> [--lang chi_sim+eng]
 * [--tessdata DIR] [--manifest FILE] [--dpi 200]}.
 */
public final class OcrCli {

    /** 成功。 */
    private static final int EXIT_OK = 0;

    /** 参数错误。 */
    private static final int EXIT_USAGE = 2;

    /** OCR 失败（损坏/资源超限/无文本）。 */
    private static final int EXIT_OCR_FAILED = 3;

    /** 引擎或语言包不可用。 */
    private static final int EXIT_ENGINE_UNAVAILABLE = 4;

    /** 工具类禁止实例化。 */
    private OcrCli() {}

    /**
     * 进程入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != EXIT_OK) {
            System.exit(exitCode);
        }
    }

    /**
     * 执行 CLI（包内可见，便于单元测试）。
     *
     * @param args 参数
     * @param out 标准输出
     * @param err 错误输出
     * @return 退出码
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String language = "chi_sim+eng";
        String tessdata = "";
        String manifestPath = "/opt/riccon/ocr/manifest.json";
        int dpi = 200;
        String imagePath = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                if (imagePath != null) {
                    err.println("errorCode=CLI_INVALID_ARGUMENT message=只能指定一个图片路径");
                    return EXIT_USAGE;
                }
                imagePath = argument;
                continue;
            }
            if (index + 1 >= args.length) {
                err.println("errorCode=CLI_INVALID_ARGUMENT message=选项 " + argument + " 缺少取值");
                return EXIT_USAGE;
            }
            String value = args[++index];
            switch (argument) {
                case "--lang" -> language = value;
                case "--tessdata" -> tessdata = value;
                case "--manifest" -> manifestPath = value;
                case "--dpi" -> {
                    try {
                        dpi = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        err.println("errorCode=CLI_INVALID_ARGUMENT message=--dpi 必须是整数");
                        return EXIT_USAGE;
                    }
                }
                default -> {
                    err.println("errorCode=CLI_INVALID_ARGUMENT message=未知参数 " + argument);
                    return EXIT_USAGE;
                }
            }
        }
        if (imagePath == null || imagePath.isBlank()) {
            err.println("usage: OcrCli <image> [--lang chi_sim+eng] [--tessdata DIR] [--manifest FILE] [--dpi 200]");
            return EXIT_USAGE;
        }
        if (dpi < 1 || dpi > 1200) {
            err.println("errorCode=CLI_INVALID_ARGUMENT message=--dpi 必须在 [1,1200]");
            return EXIT_USAGE;
        }
        java.nio.file.Path imageFile;
        try {
            imageFile = java.nio.file.Path.of(imagePath);
        } catch (RuntimeException exception) {
            err.println("errorCode=CLI_INVALID_ARGUMENT message=图片路径非法");
            return EXIT_USAGE;
        }
        OcrProperties properties;
        try {
            properties = OcrProperties.cliDefaults(language, tessdata, manifestPath);
        } catch (IllegalArgumentException exception) {
            err.println("errorCode=CLI_INVALID_ARGUMENT message=OCR 配置非法");
            return EXIT_USAGE;
        }
        OcrManifest manifest = OcrManifest.load(manifestPath);
        OcrImagePreprocessor preprocessor = new OcrImagePreprocessor(properties);
        TesseractOcrEngine engine;
        try {
            engine = new TesseractOcrEngine(properties, manifest);
        } catch (IllegalStateException exception) {
            err.println("errorCode=KB_OCR_ENGINE_UNAVAILABLE message=OCR 引擎原生库不可用");
            return EXIT_ENGINE_UNAVAILABLE;
        }
        try {
            return execute(engine, preprocessor, imageFile, dpi, out, err);
        } finally {
            // 释放 Tesseract native 句柄，避免容器退出时 Leptonica 打印 LEAK 警告。
            engine.close();
        }
    }
    /**
     * 使用给定引擎执行 OCR 并打印结果。
     *
     * @param engine OCR 引擎
     * @param preprocessor 预处理器
     * @param imagePath 图片路径
     * @param dpi DPI 提示
     * @param out 标准输出
     * @param err 错误输出
     * @return 退出码
     */
    static int execute(
            OcrEngine engine,
            OcrImagePreprocessor preprocessor,
            Path imagePath,
            int dpi,
            PrintStream out,
            PrintStream err) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(imagePath);
        } catch (IOException exception) {
            err.println("errorCode=CLI_INPUT_UNREADABLE message=图片文件不可读");
            return EXIT_USAGE;
        }
        OcrResult result;
        try {
            OcrImagePreprocessor.PreparedImage prepared = preprocessor.prepare(raw);
            result = engine.recognize(prepared.pngBytes(), new OcrOptions(1, dpi));
        } catch (OcrException exception) {
            err.println("errorCode=" + exception.errorCode() + " message=" + exception.getMessage());
            return exitCodeOf(exception.errorCode());
        } catch (RuntimeException exception) {
            err.println("errorCode=KB_OCR_FAILED message=OCR 识别失败");
            return EXIT_OCR_FAILED;
        }
        out.println("engine=" + engine.engineId());
        out.println("averageConfidence=" + result.averageConfidence());
        out.println("blocks=" + result.blocks().size());
        for (OcrBlock block : result.blocks()) {
            out.println("line=" + block.lineNo() + " confidence=" + block.confidence() + " bbox="
                    + block.bbox().x() + "," + block.bbox().y() + ","
                    + block.bbox().width() + ","
                    + block.bbox().height());
            out.println("text=" + block.text());
        }
        out.flush();
        return result.blocks().isEmpty() ? EXIT_OCR_FAILED : EXIT_OK;
    }

    /**
     * 将 OCR 错误码映射为退出码。
     *
     * @param errorCode 稳定错误码
     * @return 退出码
     */
    private static int exitCodeOf(String errorCode) {
        if (ParseErrorCodes.OCR_LANG_UNAVAILABLE.equals(errorCode)) {
            return EXIT_ENGINE_UNAVAILABLE;
        }
        return EXIT_OCR_FAILED;
    }
}
