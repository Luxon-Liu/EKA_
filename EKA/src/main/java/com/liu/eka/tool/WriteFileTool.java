package com.liu.eka.tool;

import com.liu.eka.entity.conversation.AiChatFile;
import com.liu.eka.service.ChatFileService;
import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文件写入工具：把模型产出的内容写成 HTML 文件保存到会话目录，供前端预览与导出 PDF。
 * 新建文件时不传 fileId；修改已有文件时传 fileId 做覆盖式更新（不新增记录），
 * 与「一个文件被反复修改」的产品语义一致
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteFileTool implements EkaTool {

    /** 默认文件名：模型未给名字时兜底 */
    private static final String DEFAULT_FILE_NAME = "未命名文件.html";

    /** 会话文件业务服务：落库落盘 */
    private final ChatFileService chatFileService;

    /** 工具弹性执行器：为写盘与落库提供单次超时与线程池隔离（写操作不可重试） */
    private final ToolGuard toolGuard;

    /**
     * 工具注册顺序：文件写入排在文件读取之后
     *
     * @return 顺序号 5
     */
    @Override
    public int order() {
        return 5;
    }

    /**
     * 整个文件写入工具的总超时：写盘与规整都很快，余量取中间值
     *
     * @return 总超时时长 8 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(8);
    }

    /**
     * 把内容写成 HTML 文件保存到当前会话：不传 fileId 为新建，传 fileId 为覆盖修改
     *
     * @param fileName    文件名，须以 .html 结尾（缺失后缀时自动补）；修改已有文件时可为空以沿用原名
     * @param html        完整可渲染的 HTML 内容
     * @param description 一句话用途说明，供后续检索定位，可为空
     * @param fileId      要修改的文件 ID，取自 list_files 返回的 id；为空表示新建文件
     * @param memoryId    记忆 ID（即会话 ID），由框架注入
     * @return 生成结果回执
     */
    @Tool("""
            把内容写成 HTML 文件保存到当前会话中，用户可在线预览并导出为 PDF。
            【职责范围】当用户要求生成报告、方案、文档、说明、表格等可交付产物时调用本工具；
            普通问答直接文字回复即可，不要调用本工具。
            【要求】html 参数传入完整可渲染的 HTML（样式尽量内联，便于导出 PDF 时保真）；
            fileName 用有业务含义的中文名并以 .html 结尾，例如「系统设计说明.html」；
            description 用一句话说明该文件的用途，便于后续检索定位。
            【新建与修改】新建文件时不传 fileId；修改已有文件时必须传 fileId（直接取用户消息中的文件标注，
            消息里没有时先用 list_files 查清单定位），此时 fileName 可省略以沿用原名。仅 AI 生成的文件可被修改，用户上传的源文件不可覆盖。""")
    public String writeFile(
            @P(value = "文件名，须以 .html 结尾且有业务含义，如「系统设计说明.html」；修改已有文件时可省略", required = false) String fileName,
            @P(value = "完整可渲染的 HTML 内容", required = true) String html,
            @P(value = "一句话说明该文件用途，供后续检索定位", required = false) String description,
            @P(value = "要修改的文件 ID，直接取用户消息中的文件标注；新建文件时不传", required = false) Long fileId,
            @ToolMemoryId Object memoryId) {
        // 步骤 1：解析会话 ID 并规整文件名（补 .html 后缀、空名兜底，修改时为空则沿用原名）
        Long sessionId = toSessionId(memoryId);
        String normalizedName = normalizeFileName(fileName, fileId);

        // 步骤 2：把 HTML 规整为合法 XHTML，保证前端预览与打印导出时浏览器都能正确解析
        String normalizedHtml = normalizeHtml(html);

        // 步骤 3：落库落盘（fileId 为空新建、非空覆盖），回执带文件 ID 供模型后续修改引用。
        //         写操作不可重试——新建时重跑会插入重复行，故取 NONE 策略，只要它的单次超时与线程池隔离
        AiChatFile record = toolGuard.run("file.write", ToolPolicy.NONE,
                ToolBudget.ofMillis(6000, 1, 6000),
                () -> chatFileService.saveGenerated(sessionId, fileId, normalizedName, normalizedHtml, description));
        log.info("生成会话文件：memoryId={}，fileId={}，fileName={}", sessionId, record.getId(), record.getFileName());
        return "已生成文件：[id=" + record.getId() + "] " + record.getFileName() + "（用户可在对话中预览并导出 PDF）";
    }

    /**
     * 规整文件名：修改已有文件且未给名时返回 null（表示沿用原文件名），
     * 新建时空名兜底为默认名，缺失 .html 后缀时自动补上
     *
     * @param fileName 模型给出的文件名，可为空
     * @param fileId   要修改的文件 ID，为空表示新建
     * @return 规整后的文件名；返回 null 表示沿用目标文件原名
     */
    private String normalizeFileName(String fileName, Long fileId) {
        if (fileName == null || fileName.isBlank()) {
            return fileId == null ? DEFAULT_FILE_NAME : null;
        }
        String name = fileName.trim();
        return name.toLowerCase().endsWith(".html") ? name : name + ".html";
    }

    /**
     * 把 HTML 规整为合法 XHTML：补齐 html/head/body 结构并输出 XML 语法（自闭合标签），
     * 保证前端 iframe 预览与打印导出时浏览器都能正确解析
     *
     * @param html 模型给出的 HTML，可为空
     * @return 规整后的 XHTML 文本
     */
    private String normalizeHtml(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        return document.html();
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
        throw new IllegalStateException("缺少会话上下文，无法保存文件");
    }
}
