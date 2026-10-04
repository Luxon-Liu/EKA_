package com.liu.eka.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liu.eka.common.Result;
import com.liu.eka.common.UserContext;
import com.liu.eka.entity.conversation.ChatMessageRecord;
import com.liu.eka.entity.conversation.ChatMessageVO;
import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.entity.chat.NewSession;
import com.liu.eka.service.ChatFileService;
import com.liu.eka.service.ChatMessageRecordService;
import com.liu.eka.service.ChatSessionService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆入口：会话元信息走 ai_chat_session 表，消息正文走 ai_chat_message 表；
 * 新建会话（自增记忆 ID + AI 生成标题）不进 agent loop
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@RestController
@RequestMapping("/chat-memory")
@RequiredArgsConstructor
@Validated
public class ChatMemoryController {

    /** 会话服务：会话元信息（标题、删除标记）读写 */
    private final ChatSessionService chatSessionService;

    /** 会话消息服务：消息行按会话读写 */
    private final ChatMessageRecordService chatMessageRecordService;

    /** 会话文件服务：删除会话时一并清理该会话的文件元数据与磁盘目录 */
    private final ChatFileService chatFileService;

    /**
     * 新建会话：后端生成自增记忆 ID，AI 按首问生成标题；前端拿记忆 ID 进 stream 接口
     *
     * @param firstMessage 用户首个提问，注解校验非空
     * @return 新会话（记忆 ID + 标题）
     */
    @PostMapping("/title/generate")
    public Result<NewSession> generateTitle(
            @NotBlank(message = "首个提问不能为空") @RequestParam String firstMessage) {
        // 步骤 1：日志
        log.info("生成会话标题: {}", firstMessage);
        // 步骤 2：建会话（自增 ID + 生成标题）并返回给前端
        NewSession session = chatSessionService.createSession(firstMessage);
        return Result.ok(session);
    }

    /**
     * 修改会话名：只更新会话表的标题列
     *
     * @param memoryId 记忆 ID，注解校验非空
     * @param title    新标题，注解校验非空
     * @return true 表示改名成功
     */
    @PutMapping("/title")
    public Result<Boolean> renameTitle(
            @NotNull(message = "记忆 ID 不能为空") @RequestParam Long memoryId,
            @NotBlank(message = "新标题不能为空") @RequestParam String title) {
        log.info("修改会话名：memoryId={}，新标题={}", memoryId, title);
        // 只动会话表标题列，且仅限当前登录用户自己的有效会话，防止越权改他人会话名
        boolean ok = chatSessionService.update().eq(true, "id", memoryId)
                .eq(true, "user_id", UserContext.requireUserId())
                .eq(true, "del_flag", 0)
                .set(true, "title", title)
                .update();
        return Result.ok(ok);
    }

    /**
     * 分页查询会话集合：只查有效会话，按修改时间降序，列表不带消息内容
     *
     * @param pageNum  页码（从 1 开始），默认 1
     * @param pageSize 每页条数，默认 10
     * @return 会话分页（记忆 ID + 标题 + 修改时间）
     */
    @GetMapping("/page")
    public Result<IPage<ChatSession>> pageSessions(
            @Min(value = 1, message = "页码最小为 1") @RequestParam(defaultValue = "1") long pageNum,
            @Min(value = 1, message = "每页条数最小为 1") @RequestParam(defaultValue = "20") long pageSize) {
        log.info("分页查询会话：pageNum={}，pageSize={}", pageNum, pageSize);
        // 会话元信息全在会话表，只查当前登录用户的有效会话，分页查询不触碰消息表
        IPage<ChatSession> page = chatSessionService.query().eq(true, "del_flag", 0)
                .eq(true, "user_id", UserContext.requireUserId())
                .select("id", "title", "updated_at")
                .orderByDesc(true, "updated_at")
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    /**
     * 返回一个会话对应的内容：读消息表按顺序输出「消息 ID + 单条消息 JSON」列表，
     * 前端用消息 ID 作为稳定标识（文件卡片锚点、编辑重发定位都依赖它）
     *
     * @param memoryId 记忆 ID，注解校验非空
     * @return 会话的消息视图列表（按顺序号升序）；会话不存在时抛错走全局兜底
     */
    @GetMapping("/content")
    public Result<List<ChatMessageVO>> getSessionContent(
            @NotNull(message = "记忆 ID 不能为空") @RequestParam Long memoryId) {
        log.info("查询会话内容：memoryId={}", memoryId);
        // 步骤 1：先确认会话存在、有效且归属当前登录用户，查不到说明 ID 无效或无权访问
        ChatSession session = chatSessionService.query().eq(true, "id", memoryId)
                .eq(true, "user_id", UserContext.requireUserId())
                .eq(true, "del_flag", 0)
                .select("id")
                .one();
        if (session == null) {
            throw new IllegalStateException("会话不存在");
        }

        // 步骤 2：取该会话的消息行，连同主键一起转成对外视图（正文已是单条 langchain4j JSON，无需再解析）
        List<ChatMessageRecord> rows = chatMessageRecordService.listBySessionId(memoryId);
        List<ChatMessageVO> result = new ArrayList<>(rows.size());
        for (ChatMessageRecord row : rows) {
            result.add(ChatMessageVO.builder()
                    .id(row.getId())
                    .message(row.getContent())
                    .build());
        }
        return Result.ok(result);
    }

    /**
     * 批量删除会话：逻辑删除会话行（改删除标记，不删物理行），
     * 同时清理这些会话的文件元数据与磁盘上的会话目录，避免残留无主文件
     *
     * @param memoryIds 记忆 ID 集合，注解校验非空
     * @return true 表示删除成功
     */
    @DeleteMapping("/batch")
    public Result<Boolean> batchDeleteSessions(
            @NotEmpty(message = "记忆 ID 集合不能为空") @RequestBody List<Long> memoryIds) {
        log.info("批量删除会话：{} 个，memoryIds={}", memoryIds.size(), memoryIds);
        // 步骤 1：逻辑删会话行，只允许删当前登录用户自己的会话：消息与标题保留可恢复，历史内容仍在消息表中
        boolean ok = chatSessionService.update().in(true, "id", memoryIds)
                .eq(true, "user_id", UserContext.requireUserId())
                .set(true, "del_flag", 1)
                .update();
        // 步骤 2：逐个清掉会话的文件元数据与磁盘目录（文件属物理数据，随会话一并移除）
        for (Long memoryId : memoryIds) {
            chatFileService.deleteBySession(memoryId);
        }
        return Result.ok(ok);
    }

    /**
     * 根据会话标题模糊查询会话：只查有效会话，按修改时间降序
     *
     * @param keyword 标题关键字，可为空（空即查全部有效会话）
     * @return 会话列表（记忆 ID + 标题）
     */
    @GetMapping("/search")
    public Result<List<ChatSession>> searchByTitle(
            @RequestParam(required = false) String keyword) {
        log.info("模糊查询会话：keyword={}", keyword);
        // 关键字为空时 like 条件自动跳过，等价于查当前登录用户全部有效会话；
        // 消息正文不在会话表，不参与本次查询
        List<ChatSession> list = chatSessionService.query().eq(true, "del_flag", 0)
                .eq(true, "user_id", UserContext.requireUserId())
                .like(keyword != null && !keyword.isBlank(), "title", keyword)
                .select("id", "title")
                .orderByDesc(true, "updated_at")
                .list();
        return Result.ok(list);
    }
}
