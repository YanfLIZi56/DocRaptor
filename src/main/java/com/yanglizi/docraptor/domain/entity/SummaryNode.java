package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** summary_nodes 统一节点表：node_type=LEAF(level=0) 为文本块，SUMMARY(level>=1) 为摘要节点。 */
@Data
public class SummaryNode {
    private UUID id;
    private UUID knowledgeBaseId;
    private UUID documentId;
    private UUID parentId;
    private String nodeType;
    private Short level;
    private Integer chunkIndex;
    private Integer startChunkIndex;
    private Integer endChunkIndex;
    private String content;
    private String summary;
    private Integer charCount;
    private Integer tokenCount;
    /** vector(1536)，以 '[0.1,0.2,...]' 字符串形式读写 */
    private String embedding;
    private String summaryEmbedding;
    private Integer clusterLabel;
    private Integer clusterSize;
    private String metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
