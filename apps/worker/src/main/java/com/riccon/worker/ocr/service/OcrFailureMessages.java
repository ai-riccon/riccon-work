package com.riccon.worker.ocr.service;

import com.riccon.worker.parse.domain.ParseErrorCodes;

/**
 * OCR 失败码到用户可读中文原因的映射。
 *
 * <p>写入 {@code knowledge_document.error_message}/{@code file_parse.error_message} 的文案必须
 * 稳定、可操作且不含引擎配置、内部地址或原文；前端按错误码展示“OCR 未启用/不支持该语言/识别失败”
 * 与下一步。
 */
public final class OcrFailureMessages {

    /** 工具类禁止实例化。 */
    private OcrFailureMessages() {}

    /**
     * 返回错误码对应的中文原因。
     *
     * @param errorCode 稳定错误码
     * @return 可读原因
     */
    public static String messageOf(String errorCode) {
        if (errorCode == null) {
            return "OCR 识别失败";
        }
        return switch (errorCode) {
            case ParseErrorCodes.OCR_DISABLED -> "OCR 未启用，图片仅保留原图与文件名";
            case ParseErrorCodes.OCR_LANG_UNAVAILABLE -> "不支持该语言，无法识别图片或扫描页；请联系管理员安装语言包";
            case ParseErrorCodes.OCR_TIMEOUT -> "OCR 识别超时，请稍后重试";
            case ParseErrorCodes.OCR_UNAVAILABLE -> "扫描件没有文本层且 OCR 不可用，无法抽取文本";
            case ParseErrorCodes.OCR_FAILED -> "OCR 识别失败，请稍后重试";
            case ParseErrorCodes.PARSE_RESOURCE_LIMIT -> "图片或 PDF 页超出 OCR 资源上限，已拒绝处理";
            case ParseErrorCodes.PARSE_CORRUPT -> "图片或 PDF 内容不可读或已损坏";
            case ParseErrorCodes.NO_TEXT -> "未识别到可索引文本";
            default -> "OCR 识别失败";
        };
    }
}
