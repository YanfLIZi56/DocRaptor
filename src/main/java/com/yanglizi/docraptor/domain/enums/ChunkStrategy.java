package com.yanglizi.docraptor.domain.enums;

/** 分块策略。 */
public enum ChunkStrategy {
    /** 定长滑窗 */
    FIXED_SIZE,
    /** 按空行段落聚合 */
    PARAGRAPH,
    /** 递归分隔符：段落 > 句末标点 > 换行 > 硬切 */
    RECURSIVE;

    public static boolean isValid(String v) {
        for (ChunkStrategy s : values()) {
            if (s.name().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
