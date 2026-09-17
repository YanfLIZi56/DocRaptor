package com.yanglizi.docraptor.parser;

import java.io.InputStream;

/** 文档解析接口。 */
public interface DocumentParser {

    /**
     * 从输入流抽取纯文本。
     *
     * @param fileName 原始文件名（Tika 用它做资源名提示 + 元信息）
     */
    ParsedDocument parse(InputStream in, String fileName);
}
