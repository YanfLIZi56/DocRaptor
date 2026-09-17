package com.yanglizi.docraptor.domain.enums;

/** 文档流水线 4 个步骤的状态。 */
public enum StepStatus {
    PENDING, RUNNING, SUCCESS, FAILED, SKIPPED;

    public static boolean isValid(String v) {
        for (StepStatus s : values()) {
            if (s.name().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
