package com.concert.analytics.export;

/**
 * 一次导出请求的产物：文件名 + 已编码好的字节内容。
 */
public record CsvFile(String filename, byte[] content) {
}
