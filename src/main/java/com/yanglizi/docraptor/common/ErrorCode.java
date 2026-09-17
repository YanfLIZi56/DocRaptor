package com.yanglizi.docraptor.common;

/**
 * 统一错误码表，逐条对照 docs/03-api-contract.md 第 3 节（共 31 个）。
 *
 * <p>分段：0 成功；4xxxx 客户端错误；5xxxx 服务端错误。所有接口均返回 HTTP 200（multipart 超限除外），
 * 前端只判 {@code code}。
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    /* ---------------- 4xxxx 客户端错误 ---------------- */
    PARAM_INVALID(40001, "请求参数不合法：{detail}"),
    TOP_K_EXCEEDED(40002, "topK 超出上限，最大 100"),
    MODE_INVALID(40003, "检索模式不合法，仅支持 VECTOR/BM25/HYBRID"),
    SCOPE_INVALID(40004, "检索范围不合法，仅支持 LEAF_ONLY/ALL_LEVELS/SPECIFIED_LEVEL"),
    RATIO_OUT_OF_RANGE(40005, "hybridRatio 必须在 [0,1] 区间内"),
    RRF_K_OUT_OF_RANGE(40006, "rrfK 必须在 [1,1000] 区间内"),
    FILE_TYPE_UNSUPPORTED(40007, "不支持的文件类型：{ext}，仅支持 pdf/docx/md/markdown/txt"),
    FILE_EMPTY(40008, "上传文件为空"),
    CHUNK_PARAM_INVALID(40009, "分块参数不合法：overlap 必须小于 chunkSize"),
    EXPECTED_CHUNK_INVALID(40010, "期望块不合法：{nodeId} 不是该知识库下的叶子节点"),
    LEVELS_REQUIRED(40011, "scope=SPECIFIED_LEVEL 时必须提供 levels"),
    PAYLOAD_TOO_LARGE(41301, "上传文件超过大小限制（10MB）"),

    RESOURCE_NOT_FOUND(40400, "资源不存在：{type}#{id}"),
    KNOWLEDGE_BASE_NOT_FOUND(40401, "知识库不存在"),
    DOCUMENT_NOT_FOUND(40402, "文档不存在"),
    TASK_NOT_FOUND(40403, "异步任务不存在"),
    EVAL_CASE_NOT_FOUND(40404, "评估用例不存在"),

    NAME_DUPLICATED(40901, "名称已存在：{name}"),
    CONFLICT_RUNNING_TASK(40902, "该文档已有进行中的任务：{taskId}"),
    TREE_ALREADY_EXISTS(40903, "该文档已存在构建完成的 RAPTOR 树"),
    DOCUMENT_DISABLED(40904, "文档已禁用，不参与检索"),
    DOCUMENT_NOT_READY(40905, "文档尚未完成向量化，无法检索/建树"),
    EVAL_CASE_DISABLED(40906, "用例已禁用，不参与评估"),

    /* ---------------- 5xxxx 服务端错误 ---------------- */
    DOCUMENT_PARSE_FAILED(50001, "文档解析失败：{detail}"),
    EMBEDDING_FAILED(50002, "向量化失败：{detail}"),
    LLM_FAILED(50003, "摘要生成失败：{detail}"),
    TREE_BUILD_FAILED(50004, "RAPTOR 树构建失败：{detail}"),
    DB_ERROR(50005, "数据库操作失败"),
    STORAGE_ERROR(50006, "文件存储失败：{detail}"),
    INTERNAL_ERROR(50099, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /**
     * 按顺序把消息模板里的第一处 <code>{xxx}</code> 占位替换成给定实参。
     * 例：{@code PARAM_INVALID.render("name 不能为空")} → "请求参数不合法：name 不能为空"。
     */
    public String render(Object... args) {
        String m = message;
        for (Object arg : args) {
            int i = m.indexOf('{');
            if (i < 0) {
                break;
            }
            int j = m.indexOf('}', i);
            if (j < 0) {
                break;
            }
            m = m.substring(0, i) + arg + m.substring(j + 1);
        }
        return m;
    }
}
