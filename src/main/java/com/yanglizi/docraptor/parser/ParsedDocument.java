package com.yanglizi.docraptor.parser;

import java.util.Map;

/** 解析产物：纯文本 + 元信息。 */
public record ParsedDocument(String text, Map<String, Object> metadata) {
}
