package com.yanglizi.docraptor.domain.enums;

import java.util.Locale;

/** 文件类型：按扩展名映射（.md/.markdown → MARKDOWN）。 */
public enum FileType {
    PDF("pdf"),
    DOCX("docx"),
    MARKDOWN("md"),
    TXT("txt");

    private final String extension;

    FileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    /** 由扩展名映射文件类型；不支持返回 null。 */
    public static FileType fromExtension(String ext) {
        if (ext == null) {
            return null;
        }
        String e = ext.toLowerCase(Locale.ROOT).trim();
        return switch (e) {
            case "pdf" -> PDF;
            case "docx" -> DOCX;
            case "md", "markdown" -> MARKDOWN;
            case "txt", "text" -> TXT;
            default -> null;
        };
    }

    /** 落盘时使用的扩展名。 */
    public String storedExtension() {
        return this == MARKDOWN ? "md" : extension;
    }
}
