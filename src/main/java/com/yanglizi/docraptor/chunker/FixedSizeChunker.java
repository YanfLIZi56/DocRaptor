package com.yanglizi.docraptor.chunker;

import com.yanglizi.docraptor.domain.enums.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * FIXED_SIZE：定长滑窗。
 *
 * <p>窗口长度固定等于 chunkSize，步长 = chunkSize - overlap，因此**相邻块的重叠字符数精确等于 overlap**
 * （除最后一块不足 chunkSize 外）。这是三种策略里唯一保证「重叠度精确等于配置值」的策略。
 */
public class FixedSizeChunker implements Chunker {

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED_SIZE;
    }

    @Override
    public List<TextChunk> split(String text, int chunkSize, int overlap, int minChunkChars) {
        if (ChunkerSupport.isBlank(text)) {
            return new ArrayList<>();
        }
        int size = ChunkerSupport.normalizeSize(chunkSize);
        int ov = ChunkerSupport.normalizeOverlap(overlap, size);
        int step = Math.max(1, size - ov);

        List<String> raw = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            raw.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        // 定长窗口本身已隐含重叠，不再走 mergeTrailing / applyOverlap，避免重复叠加
        return ChunkerSupport.reindex(raw);
    }
}
