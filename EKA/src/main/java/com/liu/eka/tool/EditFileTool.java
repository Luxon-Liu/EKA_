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
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文件局部修改工具：对已有 HTML 产物做「查找并替换」，只改写命中的那一处，
 * 让模型不必为了改一句话而重发整篇文档。新建与整篇重写仍由 WriteFileTool 负责
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EditFileTool implements EkaTool {

    /** 会话文件业务服务：局部替换与落库落盘 */
    private final ChatFileService chatFileService;

    /** 工具弹性执行器：为替换与落盘提供单次超时与线程池隔离（写操作不可重试） */
    private final ToolGuard toolGuard;

    /**
     * 工具注册顺序：文件修改排在写入与清单之后
     *
     * @return 顺序号 7
     */
    @Override
    public int order() {
        return 7;
    }

    /**
     * 整个文件修改工具的总超时：内容替换与落盘都是本地操作，与写入工具取同一档位
     *
     * @return 总超时时长 8 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(8);
    }

    /**
     * 对已有 HTML 文件做局部修改：把文件中唯一出现的原文片段替换为新片段
     *
     * @param fileId    要修改的文件 ID，须为 AI 生成的 HTML 文件
     * @param oldString 被替换的原文片段，须与文件内容逐字符一致且全文只出现一次
     * @param newString 替换后的新片段，传空字符串表示删除该片段
     * @param memoryId  记忆 ID（即会话 ID），由框架注入
     * @return 修改结果回执
     */
    @Tool("""
            对已有 HTML 文件做局部修改：只替换指定的片段，不必重写整篇内容。
            【职责范围】文件已生成、只需改动其中一小部分（改标题、改某段文字、调整某个数字等）时用它；
            新建文件或整篇重写请用 write_file。
            【使用方式】fileId 直接取用户消息中的文件标注，消息里没有时先用 list_files 查清单定位；
            oldString 必须与文件中的内容逐字符一致（含缩进与换行），且在文件中只出现一次——
            若提示出现多次，请在片段前后多带一些上下文以唯一定位；不确定原文时先用 read_file 读取；
            仅 AI 生成的文件可被修改，用户上传的源文件不可改。""")
    public String editFile(
            @P(value = "要修改的文件 ID，直接取用户消息中的文件标注", required = true) Long fileId,
            @P(value = "被替换的原文片段，须与文件内容逐字符一致且在文件中只出现一次", required = true) String oldString,
            @P(value = "替换后的新片段，删除该片段时传空字符串", required = true) String newString,
            @ToolMemoryId Object memoryId) {
        // 步骤 1：解析会话 ID，作为文件归属校验的依据
        Long sessionId = toSessionId(memoryId);

        // 步骤 2：在 service 内完成唯一性校验与替换并落盘落库，回执带文件 ID 供模型后续引用。
        //         写操作不可重试——重跑会把替换再执行一遍，故取 NONE 策略，只要它的单次超时与线程池隔离
        AiChatFile record = toolGuard.run("file.edit", ToolPolicy.NONE,
                ToolBudget.ofMillis(6000, 1, 6000),
                () -> chatFileService.editGenerated(sessionId, fileId, oldString, newString));
        log.info("修改会话文件：memoryId={}，fileId={}，fileName={}", sessionId, record.getId(), record.getFileName());
        return "已修改文件：[id=" + record.getId() + "] " + record.getFileName() + "（用户可在对话中预览并导出 PDF）";
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
        throw new IllegalStateException("缺少会话上下文，无法修改文件");
    }
}
