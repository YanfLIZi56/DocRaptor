package com.yanglizi.docraptor.domain.enums;

/** 检索模式：纯向量 / 纯 BM25 / RRF 混合。 */
public enum RetrievalMode {
    VECTOR, BM25, HYBRID;

    public static boolean isValid(String v) {
        for (RetrievalMode m : values()) {
            if (m.name().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
