package com.yanglizi.docraptor.domain.enums;

/** 异步任务类型。 */
public enum TaskType {
    DOC_IMPORT, DOC_PARSE, DOC_CHUNK, DOC_EMBED, RAPTOR_BUILD, EVAL_RUN;

    public static boolean isValid(String v) {
        for (TaskType t : values()) {
            if (t.name().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
