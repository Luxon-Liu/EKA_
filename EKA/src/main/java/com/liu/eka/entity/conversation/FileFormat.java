package com.liu.eka.entity.conversation;

/**
 * 会话文件格式枚举：绑定落库编码与磁盘扩展名，上传时据此校验、落盘时据此拼路径，
 * 落库时存数值编码（ai_chat_file.format 列，tinyint），不落英文枚举名
 *
 * @author Luxon
 * @date 2026/09/21
 */
public enum FileFormat {

    /** Word 文档（.docx），编码 1，由 POI 的 XWPF 解析 */
    DOCX(1, "docx"),

    /** Excel 工作簿（.xlsx），编码 2，由 POI 的 XSSF 解析 */
    XLSX(2, "xlsx"),

    /** PDF 文档（.pdf），编码 3，由 PDFBox 解析 */
    PDF(3, "pdf"),

    /** 生成的 HTML 产物（.html），编码 4，前端 iframe 直接预览 */
    HTML(4, "html");

    /** 落库数值编码 */
    private final int code;

    /** 磁盘扩展名（不含点） */
    private final String extension;

    /**
     * 构造枚举：绑定该格式的落库编码与磁盘扩展名
     *
     * @param code      落库数值编码
     * @param extension 磁盘扩展名（不含点）
     */
    FileFormat(int code, String extension) {
        this.code = code;
        this.extension = extension;
    }

    /**
     * 取落库编码
     *
     * @return 该格式在 format 列中的数值
     */
    public int code() {
        return code;
    }

    /**
     * 取磁盘扩展名
     *
     * @return 该格式的扩展名（不含点）
     */
    public String extension() {
        return extension;
    }

    /**
     * 按扩展名反查格式枚举：用于上传时校验文件类型是否受支持
     *
     * @param extension 磁盘扩展名（不含点，忽略大小写），允许为空
     * @return 匹配的格式枚举；不支持时返回 null
     */
    public static FileFormat ofExtension(String extension) {
        // 参数兜底：扩展名为空直接判为不支持
        if (extension == null) {
            return null;
        }
        // 逐个比对枚举绑定的扩展名，忽略大小写
        for (FileFormat format : values()) {
            if (format.extension.equalsIgnoreCase(extension)) {
                return format;
            }
        }
        return null;
    }

    /**
     * 按落库编码反查格式枚举：读取历史数据时把 tinyint 还原为枚举
     *
     * @param code 落库数值编码，允许为空
     * @return 匹配的格式枚举；编码为空或未知时返回 null
     */
    public static FileFormat ofCode(Integer code) {
        // 参数兜底：编码为空直接判为未知
        if (code == null) {
            return null;
        }
        // 逐个比对枚举绑定的编码
        for (FileFormat format : values()) {
            if (format.code == code) {
                return format;
            }
        }
        return null;
    }
}
