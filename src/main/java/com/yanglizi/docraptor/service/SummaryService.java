package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.ai.AiGateway;
import com.yanglizi.docraptor.algorithm.SummaryPrompts;
import com.yanglizi.docraptor.config.AiProperties;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 摘要服务：负责 System Prompt 组装、超长输入的分段摘要再合并、失败降级。
 *
 * <p>System Prompt 一律来自配置（{@code ai.summary-prompt}），不硬编码；
 * 末尾的硬约束句由 {@link SummaryPrompts} 强制追加。
 */
@Slf4j
@Service
public class SummaryService {

    private final AiGateway aiGateway;
    private final AiProperties aiProperties;
    private final DocRaptorProperties props;

    public SummaryService(AiGateway aiGateway, AiProperties aiProperties, DocRaptorProperties props) {
        this.aiGateway = aiGateway;
        this.aiProperties = aiProperties;
        this.props = props;
    }

    /** 当前生效的 System Prompt（含硬约束句），供排查与单测使用。 */
    public String systemPrompt(String overrideTemplate) {
        String template = (overrideTemplate == null || overrideTemplate.isBlank())
                ? aiProperties.getSummaryPrompt()
                : overrideTemplate;
        return SummaryPrompts.buildSystemPrompt(template, props.getSummary().getMaxSummaryChars());
    }

    /**
     * 摘要一个簇的文本。
     *
     * @param overrideTemplate 请求级的模板覆盖值，null 用配置模板
     * @return 摘要正文（已 trim）
     */
    public String summarize(String clusterText, String overrideTemplate) {
        String system = systemPrompt(overrideTemplate);
        int maxInput = Math.max(1000, props.getSummary().getMaxInputChars());
        if (clusterText.length() <= maxInput) {
            return aiGateway.chat(system, SummaryPrompts.buildUserPrompt(clusterText));
        }
        // 超长输入：分段摘要再合并，避免上下文被静默截断丢信息
        log.info("簇文本 {} 字符超过 maxInputChars={}，走分段摘要再合并", clusterText.length(), maxInput);
        List<String> segments = splitByLength(clusterText, maxInput);
        StringBuilder merged = new StringBuilder();
        for (String seg : segments) {
            String part = aiGateway.chat(system, SummaryPrompts.buildUserPrompt(seg));
            merged.append(part).append('\n');
        }
        if (merged.length() <= maxInput) {
            return aiGateway.chat(system, SummaryPrompts.buildUserPrompt(merged.toString())).trim();
        }
        return merged.toString().trim();
    }

    /**
     * 摘要失败的降级产物：用成员原文前 N 字符拼接（metadata.degraded=true）。
     */
    public String degradedSummary(List<String> memberTexts) {
        int per = Math.max(50, props.getSummary().getDegradedMemberChars());
        StringBuilder sb = new StringBuilder();
        for (String t : memberTexts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append(t.length() <= per ? t : t.substring(0, per)).append(' ');
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? "(摘要降级且无可用原文)" : out;
    }

    static List<String> splitByLength(String text, int limit) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }
}
