package com.yanglizi.docraptor.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 读写工具（entity 的 JSONB 字段按字符串读写，SQL 里 CAST 成 jsonb）。 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** JSON 对象字符串 → Map；空/非法时返回空 Map（读取侧永不抛异常）。 */
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** JSON 数组字符串 → List<String>；空/非法时返回空 List。 */
    public static List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<ArrayList<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Map → 指定类型的对象（用于把 result JSON 还原成 VO）。 */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    public static String nullToEmptyObject(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }
}
