package com.yanglizi.docraptor.algorithm;

/**
 * Token 数估算（无外部分词器）。
 *
 * <p>官方用 {@code tiktoken.get_encoding("cl100k_base")} 精确计数，本项目不引入 tiktoken，
 * 因此用启发式估算：
 * <ul>
 *   <li>CJK 字符（中日韩）≈ 1 token/字；</li>
 *   <li>其余字符（英文/数字/标点）≈ 4 字符/token。</li>
 * </ul>
 * 依据：cl100k_base 对中文大致 1 字 1~1.5 token，对英文约 4 字符 1 token。
 * 相比原先的「一律 length/4」，本估算对中文准得多 —— 后者会把 3500 token 的簇上限
 * 误判成 14000 字符，导致簇切得过大。
 *
 * <p>纯函数、确定性，可直接单测。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp) || isFullWidthPunct(cp)) {
                cjk++;
            } else if (Character.isWhitespace(cp)) {
                // 空白基本不产生 token，忽略
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 中日韩表意文字与假名、谚文。 */
    static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)      // 平假名 / 片假名
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK 基本区
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容表意
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // 谚文音节
                || (cp >= 0x20000 && cp <= 0x2FA1F); // CJK 扩展 B~F
    }

    /** 全角标点（中文标点通常也各占约 1 token）。 */
    static boolean isFullWidthPunct(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF01 && cp <= 0xFF65);
    }
}
