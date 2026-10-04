package com.liu.eka.memory;

import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 带上下文压缩的会话记忆：替代 MessageWindowChatMemory，
 * 存储层 append-only、加载层按「摘要 + 水位线之后原文」组装视图。
 * <p>
 * 与框架的交互约定：
 * <ul>
 *   <li>{@link #add} 只把本次新增消息交给存储层追加——框架内部若再调 updateMessages，
 *       收到的也永远是增量，存储层无需反推「哪条是新的」；</li>
 *   <li>{@link #messages} 返回给模型看的视图：系统提示词 + 摘要 + 水位线后原文，
 *       老工具输出已占位符化、超长消息已截断；</li>
 *   <li>压缩本身不在本类触发（messages 会被框架高频调用、须无副作用），
 *       由 CompactionService 在每轮对话开始前统一判断并落库。</li>
 * </ul>
 *
 * @author Luxon
 * @date 2026/09/17
 */
public class CompactingChatMemory implements ChatMemory {

    /** 摘要消息的统一前缀：标识这条用户消息承载的是历史摘要而非用户提问 */
    private static final String SUMMARY_PREFIX = "以下是本次会话此前内容的摘要，供你延续对话参考：\n";

    /** 记忆 ID（即会话 ID） */
    private final Object id;

    /** 会话消息存储：append-only 读写 */
    private final MysqlChatMemoryStore store;

    /** 会话服务：读取会话行上的摘要 */
    private final ChatSessionService chatSessionService;

    /** 视图组装器：工具输出占位符化与单条超长截断 */
    private final ConversationViewAssembler viewAssembler;

    /**
     * 构造会话记忆实例
     *
     * @param id                记忆 ID（会话 ID）
     * @param store             会话消息存储
     * @param chatSessionService 会话服务
     * @param viewAssembler     视图组装器
     */
    public CompactingChatMemory(Object id,
                                MysqlChatMemoryStore store,
                                ChatSessionService chatSessionService,
                                ConversationViewAssembler viewAssembler) {
        this.id = id;
        this.store = store;
        this.chatSessionService = chatSessionService;
        this.viewAssembler = viewAssembler;
    }

    /**
     * 取记忆 ID
     *
     * @return 记忆 ID（会话 ID）
     */
    @Override
    public Object id() {
        return id;
    }

    /**
     * 追加一条消息：只把这一条作为增量交给存储层，
     * 存储层负责幂等去重（系统提示词每轮重复添加）与顺序号编排
     *
     * @param message 待追加的消息
     */
    @Override
    public void add(ChatMessage message) {
        // 步骤 1：单条增量落库，绝不覆盖或删除历史行
        store.updateMessages(id, List.of(message));
    }

    /**
     * 加载给模型看的上下文视图：系统提示词 + 摘要 + 水位线之后原文；
     * 老工具输出在此处被替换为工具名占位符、超长消息被截断，均只影响视图、不改数据库
     *
     * @return 本轮发给模型的消息序列，无记录时返回空列表
     */
    @Override
    public List<ChatMessage> messages() {
        // 步骤 1：从存储层加载「最新系统提示词 + 水位线之后的消息」
        List<ChatMessage> loaded = store.getMessages(id);
        if (loaded.isEmpty()) {
            return List.of();
        }

        // 步骤 2：按视图口径加工——老的工具输出占位符化、单条超长消息截断
        List<ChatMessage> view = new ArrayList<>(viewAssembler.assemble(loaded));

        // 步骤 3：把摘要作为一条用户消息插到系统提示词之后；摘要已在生成时受 maxTokens 约束，
        //         不再参与步骤 2 的截断/占位符化，故放在加工之后插入
        String summary = currentSummary();
        if (summary != null && !summary.isBlank()) {
            int insertAt = !view.isEmpty() && view.get(0) instanceof SystemMessage ? 1 : 0;
            view.add(insertAt, UserMessage.from(SUMMARY_PREFIX + summary));
        }
        return view;
    }

    /**
     * 清空会话记忆：委托存储层物理删除该会话消息行，
     * 仅供框架 clear 语义使用，正常业务不调用（历史全量存档）
     */
    @Override
    public void clear() {
        // 步骤 1：委托存储层清空该会话消息行
        store.deleteMessages(id);
    }

    /**
     * 读取当前会话的摘要文本
     *
     * @return 摘要文本，会话不存在或尚未压缩时返回 null
     */
    private String currentSummary() {
        // 步骤 1：按主键查会话行，取摘要字段
        ChatSession session = chatSessionService.getById(((Number) id).longValue());
        return session == null ? null : session.getSummary();
    }
}
