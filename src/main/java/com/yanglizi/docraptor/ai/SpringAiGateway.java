package com.yanglizi.docraptor.ai;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI 2.0.1 的实现（{@code spring-ai-starter-model-openai}）。
 *
 * <p><b>实测结论</b>：Spring AI 2.0.1 的自动装配在 Spring Boot 4.1.1 下<b>可以正常工作</b>
 * （{@code EmbeddingModel} / {@code ChatModel} 均由 starter 正常注入，应用能启动并监听 8080）。
 * 因此本项目首选 Spring AI，未走 {@code RestClient} 兜底路线。
 *
 * <p>两个硬性参数：
 * <ul>
 *   <li>Embedding 必须传 {@code dimensions=1536}：模型默认输出 1024 维，与 {@code vector(1536)} 不符会被 PG 直接拒绝。</li>
 *   <li>Chat 必须传 {@code enable_thinking=false}：否则每次都多花约 1000 个 reasoning token，又慢又贵。</li>
 * </ul>
 */
@Slf4j
@Component
public class SpringAiGateway implements AiGateway {

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final DocRaptorProperties props;

    /**
     * 模型名从既有配置读取（{@code spring.ai.openai.chat.model} / {@code spring.ai.openai.embedding.model}）。
     *
     * <p><b>实测踩坑</b>：如果只传 {@code OpenAiEmbeddingOptions.builder().dimensions(1536).build()} 这类
     * 「不带 model 的请求级 options」，Spring AI 2.0.1 不会回填默认模型，请求会以 model=null 发出，
     * 阿里云兼容端点直接返回 {@code 404: Model not exist.}。因此这里显式带上模型名。
     */
    private final String chatModelName;
    private final String embeddingModelName;

    public SpringAiGateway(EmbeddingModel embeddingModel, ChatModel chatModel, DocRaptorProperties props,
                           @Value("${spring.ai.openai.chat.model:qwen3.7-flash}") String chatModelName,
                           @Value("${spring.ai.openai.embedding.model:qwen3.7-text-embedding}")
                           String embeddingModelName) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.props = props;
        this.chatModelName = chatModelName;
        this.embeddingModelName = embeddingModelName;
        log.info("AiGateway = SpringAiGateway（Spring AI 2.0.1 自动装配成功）chat={} embedding={}",
                chatModelName, embeddingModelName);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        int expectedDim = props.getEmbedding().getDimensions();
        int batchSize = Math.max(1, props.getEmbedding().getBatchSize());
        int maxRetries = Math.max(0, props.getEmbedding().getMaxRetries());
        long backoff = Math.max(100L, props.getEmbedding().getRetryBackoffMs());

        List<float[]> out = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            List<float[]> vectors = embedWithRetry(batch, maxRetries, backoff);
            for (float[] v : vectors) {
                if (v == null || v.length != expectedDim) {
                    throw BizException.of(ErrorCode.EMBEDDING_FAILED,
                            "返回维度 " + (v == null ? "null" : v.length) + " 与期望的 " + expectedDim
                                    + " 不一致（请检查 spring.ai.openai.embedding.dimensions=1536）");
                }
            }
            out.addAll(vectors);
        }
        return out;
    }

    @Override
    public float[] embedOne(String text) {
        List<float[]> one = embedBatch(List.of(text));
        return one.getFirst();
    }

    private List<float[]> embedWithRetry(List<String> batch, int maxRetries, long backoff) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                        .dimensions(props.getEmbedding().getDimensions())
                        .model(embeddingModelName)
                        .build();
                EmbeddingResponse resp = embeddingModel.call(new EmbeddingRequest(batch, options));
                List<Embedding> results = resp.getResults();
                if (results.size() != batch.size()) {
                    throw new IllegalStateException("embedding 返回条数 "
                            + results.size() + " 与请求条数 " + batch.size() + " 不一致");
                }
                List<float[]> vectors = new ArrayList<>(results.size());
                for (Embedding e : results) {
                    vectors.add(e.getOutput());
                }
                return vectors;
            } catch (Exception e) {
                last = new RuntimeException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                log.warn("embedding 第 {}/{} 次失败：{}", attempt + 1, maxRetries + 1, e.toString());
                if (attempt < maxRetries) {
                    sleep(backoff * (1L << attempt));
                }
            }
        }
        throw BizException.of(ErrorCode.EMBEDDING_FAILED,
                last == null ? "未知错误" : last.getMessage());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        int maxRetries = Math.max(0, props.getSummary().getMaxRetries());
        long backoff = Math.max(100L, props.getEmbedding().getRetryBackoffMs());
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                OpenAiChatOptions options = OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .temperature(props.getSummary().getTemperature())
                        .maxCompletionTokens(props.getSummary().getMaxTokens())
                        // 必须关闭思考模式：实测 completion_tokens 从 1034 降到 23
                        .extraBody(Map.of("enable_thinking", props.getSummary().isEnableThinking()))
                        .build();
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                        options);
                ChatResponse resp = chatModel.call(prompt);
                String content = resp == null || resp.getResult() == null
                        ? null : resp.getResult().getOutput().getText();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("LLM 返回空内容");
                }
                return content.trim();
            } catch (Exception e) {
                last = new RuntimeException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                log.warn("chat 第 {}/{} 次失败：{}", attempt + 1, maxRetries + 1, e.toString());
                if (attempt < maxRetries) {
                    sleep(backoff * (1L << attempt));
                }
            }
        }
        throw BizException.of(ErrorCode.LLM_FAILED, last == null ? "未知错误" : last.getMessage());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
