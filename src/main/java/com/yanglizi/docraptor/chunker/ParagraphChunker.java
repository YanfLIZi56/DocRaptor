package com.yanglizi.docraptor.chunker;

import com.yanglizi.docraptor.domain.enums.ChunkStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PARAGRAPH：按空行段落聚合。
 *
 * <p>流程：按空行切段落 → 超长段落先硬切 → 贪心打包到 chunkSize 以内 → 合并过短的尾块 → 叠加 overlap。
 * 因此块边界优先落在段落边界上，chunkSize 是软上限（叠加 overlap 后实际长度可能达到 chunkSize + overlap）。
 */
public class ParagraphChunker implements Chunker {

    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.PARAGRAPH;
    }

    @Override
    public List<TextChunk> split(String text, int chunkSize, int overlap, int minChunkChars) {
        if (ChunkerSupport.isBlank(text)) {
            return new ArrayList<>();
        }
        int size = ChunkerSupport.normalizeSize(chunkSize);
        int ov = ChunkerSupport.normalizeOverlap(overlap, size);

        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : BLANK_LINE.split(text)) {
            String p = paragraph.strip();
            if (!p.isEmpty()) {
                paragraphs.add(p);
            }
        }

        // 段落之间用空行拼接（作为软分隔），但最后一段不再补分隔符；
        // 超长段落先硬切，硬切片段之间不插分隔符，避免破坏原段落文本。
        List<String> units = new ArrayList<>();
        for (int pi = 0; pi < paragraphs.size(); pi++) {
            String p = paragraphs.get(pi);
            boolean lastParagraph = pi == paragraphs.size() - 1;
            if (p.length() <= size) {
                units.add(lastParagraph ? p : p + "\n\n");
            } else {
                List<String> pieces = ChunkerSupport.hardSplit(p, size);
                for (int i = 0; i < pieces.size(); i++) {
                    boolean lastPiece = i == pieces.size() - 1;
                    units.add(lastPiece && !lastParagraph ? pieces.get(i) + "\n\n" : pieces.get(i));
                }
            }
        }
        List<String> chunks = ChunkerSupport.pack(units, size);
        chunks = ChunkerSupport.mergeTrailing(chunks, minChunkChars);
        chunks = ChunkerSupport.applyOverlap(chunks, ov);
        return ChunkerSupport.reindex(chunks);
    }
}
