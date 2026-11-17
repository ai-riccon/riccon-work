package com.riccon.platform.domain.file;

/**
 * M02 允许上传的文件格式。
 *
 * <p>校验必须同时检查扩展名、Content-Type 与 magic bytes；枚举只表达业务允许的格式，不替代内容嗅探。
 */
public enum FileFormat {
    /** application/pdf。 */
    PDF,
    /** application/vnd.openxmlformats-officedocument.wordprocessingml.document。 */
    DOCX,
    /** application/vnd.openxmlformats-officedocument.spreadsheetml.sheet。 */
    XLSX,
    /** image/png。 */
    PNG,
    /** image/jpeg。 */
    JPEG
}
