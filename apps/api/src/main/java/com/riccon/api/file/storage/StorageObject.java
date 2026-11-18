package com.riccon.api.file.storage;

/**
 * 服务端读取到的对象正文。
 *
 * <p>只用于同源代理等小对象场景；正文已完整读入内存，大小由调用方传入的上限约束。
 * {@code contentType} 来自对象存储元数据，业务响应仍以数据库中的规范 MIME 为准。
 *
 * @param content 对象正文
 * @param contentType 对象存储记录的 MIME 类型；缺失时为 {@code null}
 */
public record StorageObject(byte[] content, String contentType) {}
