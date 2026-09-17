package com.yanglizi.docraptor.chunker;

import com.yanglizi.docraptor.domain.enums.ChunkStrategy;

import java.util.List;

/**
 * 分块策略。纯函数、无 Spring、无 DB 依赖，便于单测。
 *
 * <p>约定：
 * <ul>
 *   <li>返回块的 index 一律从 0 开始连续递增；</li>
 *   <li>空文本 / 纯空白文本返回空列表；</li>
 *   <li>文本长度 ≤ chunkSize 时返回 1 个块（若文本非空）；</li>
 *   <li>chunkSize ≤ 0 时按默认 512 处理；overlap 会被夹紧到 [0, chunkSize-1]。</li>
 * </ul>
 */
public interface Chunker {

    ChunkStrategy strategy();

    List<TextChunk> split(String text, int chunkSize, int overlap, int minChunkChars);
}
