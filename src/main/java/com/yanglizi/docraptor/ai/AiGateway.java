package com.yanglizi.docraptor.ai;

import java.util.List;

/**
 * AI 能力统一出口（Embedding + Chat）。
 *
 * <p><b>为什么要有这一层</b>：把「用不用 Spring AI」这个集成风险隔离在一个接口后面。
 * 当前实现 {@link SpringAiGateway} 走 Spring AI 2.0.1 的 {@code EmbeddingModel} / {@code ChatModel}；
 * 若将来 Spring AI 的自动装配在某个 Boot 版本上失效，只需换一个基于 Spring {@code RestClient} 直连
 * OpenAI 兼容接口（{@code /embeddings}、{@code /chat/completions}）的实现，上层服务代码零改动。
 */
public interface AiGateway {

    /**
     * 批量向量化。内部按 {@code docraptor.embedding.batch-size} 分批、失败重试，
     * 并强制 {@code dimensions=1536}（与 {@code vector(1536)} 一致）。
     *
     * @return 与入参等长、顺序一致的向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    float[] embedOne(String text);

    /** 单轮对话（摘要用），返回纯文本内容。 */
    String chat(String systemPrompt, String userPrompt);
}
