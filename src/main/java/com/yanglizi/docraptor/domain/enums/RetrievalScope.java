package com.yanglizi.docraptor.domain.enums;

/** 检索范围：仅叶子 / 全层级(折叠树) / 指定层级。 */
public enum RetrievalScope {
    LEAF_ONLY, ALL_LEVELS, SPECIFIED_LEVEL;

    public static boolean isValid(String v) {
        for (RetrievalScope s : values()) {
            if (s.name().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
