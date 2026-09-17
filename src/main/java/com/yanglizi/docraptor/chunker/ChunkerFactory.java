package com.yanglizi.docraptor.chunker;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.domain.enums.ChunkStrategy;

/** 按策略分发 Chunker。 */
public final class ChunkerFactory {

    private static final Chunker FIXED = new FixedSizeChunker();
    private static final Chunker PARAGRAPH = new ParagraphChunker();
    private static final Chunker RECURSIVE = new RecursiveChunker();

    private ChunkerFactory() {
    }

    public static Chunker get(ChunkStrategy strategy) {
        if (strategy == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "chunkStrategy 不能为空");
        }
        return switch (strategy) {
            case FIXED_SIZE -> FIXED;
            case PARAGRAPH -> PARAGRAPH;
            case RECURSIVE -> RECURSIVE;
        };
    }

    public static Chunker get(String strategy) {
        if (strategy == null || !ChunkStrategy.isValid(strategy)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "chunkStrategy 只支持 FIXED_SIZE/PARAGRAPH/RECURSIVE，收到：" + strategy);
        }
        return get(ChunkStrategy.valueOf(strategy));
    }
}
