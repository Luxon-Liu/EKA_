package com.liu.eka.tool;

import com.liu.eka.entity.EkArFile;
import com.liu.eka.entity.conversation.AiChatFile;
import com.liu.eka.entity.conversation.FileFormat;
import com.liu.eka.mapper.EkArFileMapper;
import com.liu.eka.service.ChatFileService;
import com.liu.eka.service.impl.ChatFileStorageService;
import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件读取工具：既读取会话中已上传或已生成的文件，
 * 也支持按 ek_ar_file.id 读取档案库中的原文文件，统一解析为纯文本交给模型。
 * docx 用 POI 的 XWPF、xlsx 用 POI 的 XSSF、pdf 用 PDFBox、生成的 html 直接读文本。
 * 内容超长时按固定字符数分页，模型用 page 参数翻页，避免一次性撑爆上下文
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadFileTool implements EkaTool {

    /** 单页返回的最大字符数：超出即分页，模型用 page 翻页 */
    private static final int PAGE_CHARS = 4000;

    /** scope 取值：读取当前会话内的文件 */
    private static final String SCOPE_SESSION = "session";

    /** scope 取值：按 ek_ar_file.id 读取档案库中的原文文件 */
    private static final String SCOPE_DATABASE = "database";

    /** 会话文件业务服务：按文件名定位文件 */
    private final ChatFileService chatFileService;

    /** 磁盘存储服务：读取会话文件字节 */
    private final ChatFileStorageService storage;

    /** 档案原文文件 Mapper：按 ek_ar_file.id 定位库内原文 */
    private final EkArFileMapper ekArFileMapper;

    /** 工具弹性执行器：为读盘与文档解析提供单次超时、重试与线程池隔离 */
    private final ToolGuard toolGuard;

    /** 档案原文文件存储根目录：ek_ar_file.file_path 存的是相对此目录的路径 */
    @Value("${file.archive-root}")
    private String archiveRoot;

    /**
     * 工具注册顺序：文件读取排在检索与查库之后
     *
     * @return 顺序号 4
     */
    @Override
    public int order() {
        return 4;
    }

    /**
     * 整个文件读取工具的总超时：大文件解析较慢，给足余量
     *
     * @return 总超时时长 8 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(8);
    }

    /**
     * 读取会话内或档案库内某个文件的正文内容，内容过长时按页返回
     *
     * @param fileId   要读取的文件 ID：session 范围取会话文件的数字 ID，database 范围取 ek_ar_file.id
     * @param scope    读取范围，session 表示当前会话的文件，database 表示档案库中的原文文件，必传
     * @param page     页码，从 1 开始，为空按第 1 页
     * @param memoryId 记忆 ID（即会话 ID），由框架注入
     * @return 该页的文件正文；超长时附翻页提示
     */
    @Tool("""
            读取某个文件的正文内容，支持读取当前会话中已上传或已生成的文件，也支持按 ek_ar_file.id 读取档案库中的原文文件。
            【职责范围】当用户要求查看、总结、分析、翻译某个文件内容时调用本工具；
            或当需要基于已生成文件的内容继续加工时调用。
            【使用方式】fileId 取用户消息中的文件标注、list_files 返回的 id，或查询 ek_ar_file 得到的原文文件 ID，不要臆造 id；
            引用的是更早的会话文件、消息里没有标注时，先用 list_files 查清单定位；
            scope 用于指明文件范围：文件属于当前会话（用户上传或本会话生成）时传 session，
            要读取档案库中的原文文件（按 ek_ar_file.id）时传 database，该参数必传；
            内容超长时本工具按页返回，可用 page 参数翻页读取后续内容。
            支持的文件类型：docx、xlsx、pdf 与生成的 html""")
    public String readFile(
            @P(value = "要读取的文件 ID：session 范围取会话文件的数字 ID，database 范围取 ek_ar_file.id", required = true) String fileId,
            @P(value = "文件范围：session=当前会话的文件，database=档案库中的原文文件（按 ek_ar_file.id），必传", required = true) String scope,
            @P(value = "页码，从 1 开始，默认第 1 页；内容超长时用其翻页", required = false) Integer page,
            @ToolMemoryId Object memoryId) {
        // 步骤 1：按 scope 分派读取路径，非法 scope 直接报错
        String fileName;
        String text;
        if (SCOPE_SESSION.equalsIgnoreCase(scope)) {
            // 步骤 2：会话文件——按数字 ID 定位，校验会话归属，解析交给弹性执行器
            AiChatFile record = chatFileService.findOwned(toSessionId(memoryId), parseSessionFileId(fileId));
            if (record == null) {
                throw new IllegalArgumentException("会话文件不存在：" + fileId
                        + "。请先用 list_files 确认当前会话的文件 ID");
            }
            fileName = record.getFileName();
            text = toolGuard.run("file.read", ToolPolicy.TRANSIENT,
                    ToolBudget.ofMillis(6000, 2, 7000),
                    () -> extractText(record));
        } else if (SCOPE_DATABASE.equalsIgnoreCase(scope)) {
            // 步骤 3：档案原文文件——按 ek_ar_file.id 定位，从档案根目录读盘并解析
            EkArFile archive = ekArFileMapper.selectById(fileId);
            if (archive == null) {
                throw new IllegalArgumentException("档案原文文件不存在：" + fileId
                        + "。请先查询 ek_ar_file 确认文件 ID");
            }
            fileName = archive.getFileName();
            text = toolGuard.run("file.read", ToolPolicy.TRANSIENT,
                    ToolBudget.ofMillis(6000, 2, 7000),
                    () -> extractArchiveText(archive));
        } else {
            throw new IllegalArgumentException("scope 取值非法：" + scope + "，仅支持 session 或 database");
        }

        // 步骤 4：按固定字符数切页，返回指定页并附翻页提示
        return renderPage(fileName, text, page);
    }

    /**
     * 解析档案原文文件：按文件名扩展名分发到对应解析库
     *
     * @param archive 档案原文文件元数据行
     * @return 文件全文纯文本
     */
    private String extractArchiveText(EkArFile archive) {
        // 步骤 1：拼出档案根目录下的绝对路径并读出字节
        Path path = Path.of(archiveRoot, archive.getFilePath());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("档案原文读取失败：" + archive.getFileName(), e);
        }

        // 步骤 2：按扩展名分发解析（档案原文以 pdf 为主，其余格式同样支持）
        String name = archive.getFileName() == null ? "" : archive.getFileName().toLowerCase();
        if (name.endsWith(".pdf")) {
            return extractPdf(bytes);
        }
        if (name.endsWith(".docx")) {
            return extractDocx(bytes);
        }
        if (name.endsWith(".xlsx")) {
            return extractXlsx(bytes);
        }
        throw new IllegalStateException("不支持的档案文件格式：" + archive.getFileName());
    }

    /**
     * 把会话文件的字符串 ID 解析为数字主键
     *
     * @param fileId 字符串形式的会话文件 ID
     * @return 数字主键
     */
    private Long parseSessionFileId(String fileId) {
        try {
            return Long.valueOf(fileId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("session 范围的文件 ID 必须是数字：" + fileId);
        }
    }

    /**
     * 按文件格式分发解析：html 直接读文本，其余交给对应解析库
     *
     * @param record 文件元数据行
     * @return 文件全文纯文本
     */
    private String extractText(AiChatFile record) {
        // 步骤 1：把落库编码还原为格式枚举，未知编码直接报错（脏数据兜底）
        FileFormat format = FileFormat.ofCode(record.getFormat());
        if (format == null) {
            throw new IllegalStateException("文件格式异常，无法解析：" + record.getFileName());
        }
        // 步骤 2：HTML 产物本身就是文本，直接读，不走二进制解析分支
        if (format == FileFormat.HTML) {
            return storage.readText(record.getStoredPath());
        }
        // 步骤 3：其余格式按扩展名分发解析（HTML 已在上一步返回，default 仅作穷尽性兜底）
        byte[] bytes = storage.readBytes(record.getStoredPath());
        return switch (format) {
            case DOCX -> extractDocx(bytes);
            case XLSX -> extractXlsx(bytes);
            case PDF -> extractPdf(bytes);
            default -> throw new IllegalStateException("无法解析的文件格式：" + format);
        };
    }

    /**
     * 解析 docx：按正文顺序遍历段落与表格，段落取文本、表格按行拼成竖线分隔
     *
     * @param bytes docx 文件字节
     * @return 提取出的纯文本
     */
    private String extractDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            // 逐个正文元素处理：段落取文本，表格逐行拼接
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String line = paragraph.getText();
                    if (line != null && !line.isBlank()) {
                        text.append(line).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText());
                        }
                        text.append(String.join(" | ", cells)).append('\n');
                    }
                }
            }
            return text.toString();
        } catch (IOException e) {
            throw new IllegalStateException("docx 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 xlsx：逐个工作表遍历行，单元格用 DataFormatter 取显示值
     *
     * @param bytes xlsx 文件字节
     * @return 提取出的纯文本
     */
    private String extractXlsx(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            DataFormatter formatter = new DataFormatter();
            // 逐个工作表处理，行内单元格按竖线分隔
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                text.append("【工作表】").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell));
                    }
                    text.append(String.join(" | ", cells)).append('\n');
                }
            }
            return text.toString();
        } catch (IOException e) {
            throw new IllegalStateException("xlsx 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 pdf：用 PDFBox 提取全文本
     *
     * @param bytes pdf 文件字节
     * @return 提取出的纯文本
     */
    private String extractPdf(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new IllegalStateException("pdf 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按固定字符数分页渲染：返回指定页正文，未读完时附下一页提示
     *
     * @param fileName 展示文件名，用于回执
     * @param text     文件全文
     * @param page     请求页码，为空或越界时收敛到有效范围
     * @return 该页正文与翻页提示
     */
    private String renderPage(String fileName, String text, Integer page) {
        // 步骤 1：计算总页数（空文本也算一页）
        int totalPages = Math.max(1, (text.length() + PAGE_CHARS - 1) / PAGE_CHARS);
        int current = (page == null || page < 1) ? 1 : Math.min(page, totalPages);

        // 步骤 2：截取当前页区间
        int from = (current - 1) * PAGE_CHARS;
        int to = Math.min(text.length(), from + PAGE_CHARS);
        String slice = text.substring(from, to);

        // 步骤 3：拼接回执，未读完时提示下一页页码
        StringBuilder result = new StringBuilder();
        result.append("【文件】").append(fileName)
                .append("（第 ").append(current).append('/').append(totalPages).append(" 页，共 ")
                .append(text.length()).append(" 字符）\n");
        result.append(slice);
        if (current < totalPages) {
            result.append("\n\n[内容未完，请用 page=").append(current + 1).append(" 继续读取]");
        }
        return result.toString();
    }

    /**
     * 把框架注入的 memoryId 统一转成会话 ID
     *
     * @param memoryId 框架注入的记忆 ID，实际为 Long
     * @return 会话 ID
     */
    private Long toSessionId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("缺少会话上下文，无法定位文件");
    }
}
