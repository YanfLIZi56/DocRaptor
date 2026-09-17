package com.yanglizi.docraptor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 既有 {@code ai.*} 配置的绑定（不改名、不改语义）。
 * {@code ai.summary-prompt} 是正式摘要 System Prompt（由 Lead 维护），摘要调用一律从这里注入，不硬编码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 对应 application-local.yml 的 ai.summary-prompt。 */
    private String summaryPrompt = "";
}
