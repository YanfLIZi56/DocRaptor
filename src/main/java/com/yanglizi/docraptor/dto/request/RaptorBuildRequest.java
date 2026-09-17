package com.yanglizi.docraptor.dto.request;

import lombok.Data;

/** POST /api/raptor/trees 请求体（契约 5.1）。 */
@Data
public class RaptorBuildRequest {
    private String documentId;
    private Integer maxLevel;
    private Boolean forceRebuild;
    private Integer umapNNeighbors;
    private Double umapMinDist;
    private Integer gmmMaxClusters;
    private String gmmCovarianceType;
    private String summaryPrompt;
}
