package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** async_task 表：异步任务状态与进度落库。 */
@Data
public class AsyncTask {
    private UUID id;
    private String taskType;
    private UUID knowledgeBaseId;
    private UUID documentId;
    private String status;
    private Integer progress;
    private String currentStage;
    private String progressMessage;
    private String payload;
    private String result;
    private String errorMessage;
    private Integer retryCount;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime heartbeatAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
