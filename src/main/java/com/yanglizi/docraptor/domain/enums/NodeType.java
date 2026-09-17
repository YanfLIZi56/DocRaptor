package com.yanglizi.docraptor.domain.enums;

/** 节点类型：叶子文本块 / LLM 摘要节点。 */
public enum NodeType {
    LEAF, SUMMARY;

    public static boolean isValid(String v) {
        return "LEAF".equals(v) || "SUMMARY".equals(v);
    }
}
