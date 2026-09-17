package com.yanglizi.docraptor.algorithm;

import java.math.BigDecimal;
import java.util.List;

/**
 * pgvector JDBC 取值工具。
 *
 * <p>PGVector 的 vector 列对 PG JDBC 驱动而言是 USER-DEFINED 类型，不能直接绑定 float[]。
 * 本项目统一采用「字符串字面量 + ::vector 转换」：Java 侧拼出 {@code [0.1,0.2,...]} 字符串，
 * SQL 里写 {@code #{queryVector}::vector}（已实测可用，无需额外引入 PGvector 类型）。
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /** float[] → pgvector 字面量 '[0.1,0.2,...]'。 */
    public static String toLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** double[] → pgvector 字面量。 */
    public static String toLiteral(double[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(BigDecimal.valueOf(vector[i]).toPlainString());
        }
        sb.append(']');
        return sb.toString();
    }

    /** pgvector 字面量 → double[]（用于向量预览等只读场景）。 */
    public static double[] parse(String literal) {
        if (literal == null) {
            return new double[0];
        }
        String s = literal.trim();
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isBlank()) {
            return new double[0];
        }
        String[] parts = s.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    /** List<float[]> → double[][]（Smile 只吃 double[][]）。 */
    public static double[][] toMatrix(List<float[]> vectors) {
        double[][] m = new double[vectors.size()][];
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            double[] row = new double[v.length];
            for (int j = 0; j < v.length; j++) {
                row[j] = v[j];
            }
            m[i] = row;
        }
        return m;
    }

    /** 向量前 n 维预览（契约 4.9 的 embeddingPreview）。 */
    public static double[] preview(String literal, int n) {
        double[] all = parse(literal);
        if (all.length <= n) {
            return all;
        }
        double[] head = new double[n];
        System.arraycopy(all, 0, head, 0, n);
        return head;
    }
}
