package com.yanglizi.docraptor.algorithm;

/**
 * 摘要 Prompt 组装（纯函数，便于无 Spring 单测）。
 *
 * <p>System Prompt = 配置模板（默认取 {@code ai.summary-prompt}） + 末尾**不可删改**的硬约束句。
 * 硬约束句来自 docs/01-architecture.md 5.3，无论调用方如何覆盖模板，都会被追加到末尾：
 * <pre>
 * 再次强调：仅基于提供的文本块进行总结，禁止添加任何未在原文中出现的信息。只做信息压缩，不添加新事实。
 * </pre>
 */
public final class SummaryPrompts {

    /** 硬约束句 1（单测断言其必须出现）。 */
    public static final String CONSTRAINT_SENTENCE_1 = "仅基于提供的文本块进行总结，禁止添加任何未在原文中出现的信息。";
    /** 硬约束句 2（单测断言其必须出现）。 */
    public static final String CONSTRAINT_SENTENCE_2 = "只做信息压缩，不添加新事实。";

    public static final String HARD_CONSTRAINT =
            "再次强调：" + CONSTRAINT_SENTENCE_1 + CONSTRAINT_SENTENCE_2;

    /** 簇内成员的分隔标注格式：{@code [块#index]} */
    public static final String MEMBER_HEADER_FORMAT = "[块#%d]";

    private SummaryPrompts() {
    }

    /**
     * 组装 System Prompt。
     *
     * @param template        配置的模板（{@code ai.summary-prompt} 或请求里的覆盖值），可为空
     * @param maxSummaryChars 摘要长度上限，用于替换模板里的 {@code {maxSummaryChars}} 占位符
     */
    public static String buildSystemPrompt(String template, int maxSummaryChars) {
        String base = template == null ? "" : template;
        base = base.replace("{maxSummaryChars}", String.valueOf(maxSummaryChars)).strip();
        if (base.isEmpty()) {
            return HARD_CONSTRAINT;
        }
        return base + "\n\n" + HARD_CONSTRAINT;
    }

    /** 组装 User Prompt：待摘要的文本块（带 [块#idx] 标注）。 */
    public static String buildUserPrompt(String clusterText) {
        return "待摘要的文本块：\n---\n" + (clusterText == null ? "" : clusterText) + "\n---";
    }

    /** 单个成员的标注行。 */
    public static String memberHeader(int index) {
        return String.format(MEMBER_HEADER_FORMAT, index);
    }
}
