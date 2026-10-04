package com.liu.eka.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.liu.eka.entity.conversation.ChatFileVO;
import com.liu.eka.entity.conversation.FileFormat;
import com.liu.eka.entity.conversation.FileKind;
import com.liu.eka.service.ChatFileService;
import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 文件清单工具：列出当前会话已上传与已生成的文件，支持关键词过滤与分页，
 * 是模型定位「用户提到的某个较早文件」的统一入口（文件存独立表，不受上下文压缩影响）
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListFileTool implements EkaTool {

    /** 单页返回的最大文件数 */
    private static final int PAGE_SIZE = 20;

    /** 最后修改时间的展示格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 会话文件业务服务：查询文件清单 */
    private final ChatFileService chatFileService;

    /** 工具弹性执行器：为数据库查询提供单次超时、重试与线程池隔离 */
    private final ToolGuard toolGuard;

    /**
     * 工具注册顺序：文件清单排在最后
     *
     * @return 顺序号 6
     */
    @Override
    public int order() {
        return 6;
    }

    /**
     * 整个文件清单工具的总超时：单表查询很快
     *
     * @return 总超时时长 3 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(3);
    }

    /**
     * 列出当前会话的文件清单，支持关键词过滤与分页
     *
     * @param keyword  关键词，按文件名或用途描述模糊过滤，可为空表示不过滤
     * @param page     页码，从 1 开始，为空按第 1 页
     * @param memoryId 记忆 ID（即会话 ID），由框架注入
     * @return Markdown 形式的文件清单；无文件、无匹配或页码越界时返回提示文案
     */
    @Tool("""
            列出当前会话中已上传与已生成的文件清单。
            【职责范围】当用户提及某个文件但你无法确定是哪一个（尤其是较早生成或上传的文件）时，
            必须先调用本工具查看清单，再决定读取或修改哪个文件，不要凭猜测臆造文件 ID。
            【使用方式】keyword 可按文件名或用途描述模糊过滤；文件较多时用 page 翻页。
            返回内容包括：文件 ID（read_file / write_file 引用文件时使用）、文件名、来源（上传/生成）、
            格式、用途描述与最后修改时间。""")
    public String listFiles(
            @P(value = "关键词，按文件名或用途描述模糊过滤，可为空表示不过滤", required = false) String keyword,
            @P(value = "页码，从 1 开始，默认第 1 页", required = false) Integer page,
            @ToolMemoryId Object memoryId) {
        // 步骤 1：关键词过滤、排序与分页全部交给数据库，只取当前页的数据；DB 瞬时抖动交由弹性执行器重试
        Long sessionId = toSessionId(memoryId);
        int current = (page == null || page < 1) ? 1 : page;
        IPage<ChatFileVO> result = toolGuard.run("file.list", ToolPolicy.TRANSIENT,
                ToolBudget.ofMillis(1000, 2, 2000),
                () -> chatFileService.listPage(sessionId, keyword, current, PAGE_SIZE));

        // 步骤 2：查空分两种情况——有数据却查空说明页码超了，明确告知实际页数让模型重试，
        //         比静默收敛到最后一页更诚实（否则模型会误以为任意大页码都合法）
        List<ChatFileVO> files = result.getRecords();
        if (files.isEmpty()) {
            if (result.getTotal() > 0) {
                return "页码超出范围：文件共 " + result.getPages() + " 页，请用 page=1.."
                        + result.getPages() + " 重试。";
            }
            return keyword == null || keyword.isBlank()
                    ? "当前会话还没有任何文件。"
                    : "没有匹配「" + keyword + "」的文件，可换个关键词或去掉关键词查看全部。";
        }

        // 步骤 3：渲染 Markdown 清单，附分页提示
        StringBuilder output = new StringBuilder();
        output.append("当前会话文件（第 ").append(result.getCurrent()).append('/').append(result.getPages())
                .append(" 页，共 ").append(result.getTotal()).append(" 个）：\n\n");
        for (ChatFileVO file : files) {
            // 把落库编码还原为可读文案，未知编码降级为「未知」而非静默错报
            FileKind kind = FileKind.ofCode(file.getKind());
            FileFormat format = FileFormat.ofCode(file.getFormat());
            String kindText = kind == FileKind.GENERATED ? "AI 生成" : "用户上传";
            output.append("- [id=").append(file.getId()).append("] ").append(file.getFileName())
                    .append("｜来源：").append(kind == null ? "未知" : kindText)
                    .append("｜格式：").append(format == null ? "未知" : format.extension());
            if (file.getDescription() != null && !file.getDescription().isBlank()) {
                output.append("｜说明：").append(file.getDescription());
            }
            if (file.getUpdatedAt() != null) {
                output.append("｜最后修改：").append(file.getUpdatedAt().format(TIME_FORMATTER));
            }
            output.append('\n');
        }
        if (result.getCurrent() < result.getPages()) {
            output.append("\n[还有更多，请用 page=").append(result.getCurrent() + 1).append(" 查看]");
        }
        return output.toString();
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
        throw new IllegalStateException("缺少会话上下文，无法列出文件");
    }
}
