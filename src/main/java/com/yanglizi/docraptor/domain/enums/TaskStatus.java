package com.yanglizi.docraptor.domain.enums;

import java.util.Set;

/** 异步任务状态。 */
public enum TaskStatus {
    PENDING, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELED;

    private static final Set<String> TERMINAL = Set.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED");

    public static boolean isValid(String v) {
        for (TaskStatus t : values()) {
            if (t.name().equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 前端轮询到达这些状态后应停止轮询。 */
    public static boolean isTerminal(String v) {
        return TERMINAL.contains(v);
    }
}
