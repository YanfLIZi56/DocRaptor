package com.yanglizi.docraptor.chunker;

/** 一个文本块：index 为块序号（同一文档内从 0 开始连续递增）。 */
public record TextChunk(int index, String text) {
}
