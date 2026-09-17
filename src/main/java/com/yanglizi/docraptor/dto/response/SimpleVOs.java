package com.yanglizi.docraptor.dto.response;

import lombok.Data;

/** 轻量响应对象集合（契约里的几个单字段返回体）。 */
public final class SimpleVOs {

    private SimpleVOs() {
    }

    /** 异步任务触发返回（契约 1.3；建树额外带 documentId）。 */
    @Data
    public static class TaskSubmitVO {
        private String taskId;
        private String status;
        /** 仅 POST /api/raptor/trees 返回 */
        private String documentId;

        public static TaskSubmitVO of(String taskId, String status) {
            TaskSubmitVO vo = new TaskSubmitVO();
            vo.taskId = taskId;
            vo.status = status;
            return vo;
        }
    }

    /** 文档上传返回（契约 4.6）。 */
    @Data
    public static class UploadVO {
        private String documentId;
        private String taskId;
        private String fileName;
        private String fileType;
        private Long fileSize;
        private String status;
    }

    /** 启用/禁用返回（契约 4.8）。 */
    @Data
    public static class DocumentEnabledVO {
        private String id;
        private Boolean enabled;
        private Long updatedAt;
    }

    /** 删除知识库返回（契约 4.5）。 */
    @Data
    public static class KnowledgeBaseDeleteVO {
        private String id;
        private Long deletedDocuments;
        private Long deletedNodes;
        private Long deletedEvalCases;
    }

    /** 通用删除返回（契约 6.4）。 */
    @Data
    public static class DeletedVO {
        private String id;
        private Boolean deleted;

        public static DeletedVO of(String id) {
            DeletedVO vo = new DeletedVO();
            vo.id = id;
            vo.deleted = true;
            return vo;
        }
    }
}
