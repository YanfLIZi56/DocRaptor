package com.yanglizi.docraptor.chunker;

import com.yanglizi.docraptor.domain.enums.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RECURSIVE：递归分隔符切分，分隔符优先级 段落 > 换行 > 句末标点 > 逗号/顿号/空格 > 硬切。
 *
 * <p>与 {@link ParagraphChunker} 的差别：段落内部还会继续按句子、再按子句递归切，直到单元不超过 chunkSize，
 * 因此块边界更贴合语义单元；chunkSize 同样是软上限。
 */
public class RecursiveChunker implements Chunker {

    /** 注意：分隔符用「保留分隔符」的正则（lookbehind），保证文本不丢字符。 */
    private static final String[] SEPARATORS = {
            "\\n\\s*\\n",              // 段落
            "\\n",                      // 换行
            "(?<=[。！？；!?;])",        // 句末标点
            "(?<=[，,、])",              // 子句标点
            " "                         // 空格
    };

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.RECURSIVE;
    }

    @Override
    public List<TextChunk> split(String text, int chunkSize, int overlap, int minChunkChars) {
        if (ChunkerSupport.isBlank(text)) {
            return new ArrayList<>();
        }
        int size = ChunkerSupport.normalizeSize(chunkSize);
        int ov = ChunkerSupport.normalizeOverlap(overlap, size);

        List<String> units = recursiveSplit(text, size, 0);
        List<String> chunks = ChunkerSupport.pack(units, size);
        chunks = ChunkerSupport.mergeTrailing(chunks, minChunkChars);
        chunks = ChunkerSupport.applyOverlap(chunks, ov);
        return ChunkerSupport.reindex(chunks);
    }

    private List<String> recursiveSplit(String text, int limit, int sepIndex) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        if (text.length() <= limit) {
            out.add(text);
            return out;
        }
        if (sepIndex >= SEPARATORS.length) {
            return ChunkerSupport.hardSplit(text, limit);
        }
        String[] parts = text.split(SEPARATORS[sepIndex]);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() <= limit) {
                out.add(part);
            } else {
                out.addAll(recursiveSplit(part, limit, sepIndex + 1));
            }
        }
        return out;
    }

    /** 供单测断言分隔符表用。 */
    static Pattern separatorPattern() {
        return Pattern.compile(String.join("|", SEPARATORS));
    }
}
