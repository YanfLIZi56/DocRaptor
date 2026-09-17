package com.yanglizi.docraptor.chunker;

import java.util.ArrayList;
import java.util.List;

/** 三种分块策略共用的打包/重叠/尾块合并逻辑。 */
final class ChunkerSupport {

    static final int DEFAULT_CHUNK_SIZE = 512;

    private ChunkerSupport() {
    }

    static int normalizeSize(int chunkSize) {
        return chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
    }

    /** overlap 必须落在 [0, chunkSize-1]。 */
    static int normalizeOverlap(int overlap, int chunkSize) {
        if (overlap < 0) {
            return 0;
        }
        return Math.min(overlap, chunkSize - 1);
    }

    /** 贪心把若干「单元」打包成不超过 size 的块。单个单元超过 size 时自成一个块。 */
    static List<String> pack(List<String> units, int size) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String unit : units) {
            if (unit.isEmpty()) {
                continue;
            }
            if (buf.length() == 0) {
                buf.append(unit);
                continue;
            }
            if (buf.length() + unit.length() <= size) {
                buf.append(unit);
            } else {
                chunks.add(buf.toString());
                buf.setLength(0);
                buf.append(unit);
            }
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString());
        }
        return chunks;
    }

    /**
     * 尾块小于 minChunkChars 时并回前一块（避免碎片）。
     * 必须在 {@link #applyOverlap} 之前调用，否则会因重叠前缀而重复文本。
     */
    static List<String> mergeTrailing(List<String> chunks, int minChunkChars) {
        if (minChunkChars <= 0 || chunks.size() < 2) {
            return chunks;
        }
        String last = chunks.get(chunks.size() - 1);
        if (last.length() >= minChunkChars) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks.subList(0, chunks.size() - 1));
        out.set(out.size() - 1, out.get(out.size() - 1) + last);
        return out;
    }

    /**
     * 给相邻块叠加重叠：把前一块末尾 overlap 个字符前置到当前块。
     * 调用后满足不变式：chunks[i+1] 以 chunks[i] 的末尾 min(overlap, len) 个字符开头（i ≥ 0）。
     */
    static List<String> applyOverlap(List<String> chunks, int overlap) {
        if (overlap <= 0 || chunks.size() < 2) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks.size());
        out.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = out.get(i - 1);
            int take = Math.min(overlap, prev.length());
            out.add(prev.substring(prev.length() - take) + chunks.get(i));
        }
        return out;
    }

    /** 硬切成不超过 limit 的片段。 */
    static List<String> hardSplit(String text, int limit) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /** 拼装最终结果并重新编号 0..n-1。 */
    static List<TextChunk> reindex(List<String> chunks) {
        List<TextChunk> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            out.add(new TextChunk(i, chunks.get(i)));
        }
        return out;
    }

    static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
