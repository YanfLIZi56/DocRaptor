package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.Map;

/** 异步任务对象（契约 7.1 / 7.2）。 */
@Data
public class AsyncTaskVO {
    private String taskId;
    private String taskType;
    private String status;
    private Integer progress;
    private String currentStage;
    private String progressMessage;
    private String knowledgeBaseId;
    private String documentId;
    private String documentName;
    private Integer retryCount;
    private String errorMessage;
    private Map<String, Object> payload;
    private Map<String, Object> result;
    private Long createdAt;
    private Long startedAt;
    private Long finishedAt;
    private Long heartbeatAt;
}
