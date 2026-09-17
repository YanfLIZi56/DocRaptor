package com.yanglizi.docraptor.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 时间/UUID 工具：契约要求时间字段为毫秒级 Unix 时间戳（number）。 */
public final class TimeUtils {

    private TimeUtils() {
    }

    /** 转毫秒时间戳；null 安全。 */
    public static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }

    /** 毫秒时间戳 → OffsetDateTime（UTC），null 安全。 */
    public static OffsetDateTime fromMillis(Long millis) {
        return millis == null ? null : OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    public static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析 UUID 字符串；非法时抛 40001。
     */
    public static UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, fieldName + " 不能为空");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, fieldName + " 不是合法 UUID：" + value);
        }
    }
}
