package com.yanglizi.docraptor.parser;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 Tika 3.2.2 AutoDetectParser 的解析实现，PDF/DOCX/MD/TXT 统一走同一套探测逻辑。
 *
 * <p><b>实测要点</b>：Tika 3.x 里 {@code Metadata.RESOURCE_NAME_KEY} 已废弃，
 * 必须用 {@code TikaCoreProperties.RESOURCE_NAME_KEY}（已按此实现）。
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    /** 零宽字符与软连字符 */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00AD]");
    private static final Pattern CRLF = Pattern.compile("\\r\\n?");
    private static final Pattern NBSP = Pattern.compile("\\u00A0");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");

    private final DocRaptorProperties props;

    public TikaDocumentParser(DocRaptorProperties props) {
        this.props = props;
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            // -1 → 不限制写入长度（上限由 docraptor.import.max-parse-chars 在归一化后统一截断）
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            parser.parse(in, handler, metadata, context);

            String raw = handler.toString();
            String text = props.getChunk().isNormalizeWhitespace() ? normalize(raw) : raw;

            int max = props.getImportConfig().getMaxParseChars();
            Map<String, Object> meta = extractMetadata(metadata);
            if (max > 0 && text.length() > max) {
                text = text.substring(0, max);
                meta.put("truncated", true);
            }
            return new ParsedDocument(text, meta);
        } catch (Exception e) {
            log.warn("Tika 解析失败 fileName={} err={}", fileName, e.toString());
            throw BizException.of(ErrorCode.DOCUMENT_PARSE_FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 归一化：统一换行、去零宽字符、合并多余空行、去行尾空白。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String t = CRLF.matcher(raw).replaceAll("\n");
        t = ZERO_WIDTH.matcher(t).replaceAll("");
        t = NBSP.matcher(t).replaceAll(" ");
        t = TRAILING_SPACES.matcher(t).replaceAll("\n");
        t = MANY_BLANK_LINES.matcher(t).replaceAll("\n\n");
        return t.strip();
    }

    private static Map<String, Object> extractMetadata(Metadata metadata) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "title", metadata.get(TikaCoreProperties.TITLE));
        putIfPresent(meta, "author", metadata.get(TikaCoreProperties.CREATOR));
        putIfPresent(meta, "tikaContentType", metadata.get(Metadata.CONTENT_TYPE));
        String pages = metadata.get("xmpTPg:NPages");
        if (pages == null) {
            pages = metadata.get("meta:page-count");
        }
        if (pages != null) {
            try {
                meta.put("pageCount", Integer.parseInt(pages.trim()));
            } catch (NumberFormatException ignore) {
                // 非数字页码忽略
            }
        }
        return meta;
    }

    private static void putIfPresent(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.isBlank()) {
            meta.put(key, value);
        }
    }
}
