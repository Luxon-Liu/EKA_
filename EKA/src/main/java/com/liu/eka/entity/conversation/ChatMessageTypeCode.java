package com.liu.eka.entity.conversation;

import dev.langchain4j.data.message.ChatMessageType;

/**
 * 会话消息类型编码：与 ai_chat_message.message_type 列的取值一一对应，
 * 库里存数值编码（列注释同步写明含义），不直接落英文枚举名
 *
 * @author Luxon
 * @date 2026/09/17
 */
public enum ChatMessageTypeCode {

    /** 系统提示消息，编码 1（对应 langchain4j 的 SYSTEM） */
    SYSTEM(1),

    /** 用户提问消息，编码 2（对应 langchain4j 的 USER） */
    USER(2),

    /** AI 回复消息，编码 3（对应 langchain4j 的 AI） */
    AI(3),

    /** 工具执行结果消息，编码 4（对应 langchain4j 的 TOOL_EXECUTION_RESULT） */
    TOOL_EXECUTION_RESULT(4),

    /** 自定义消息，编码 5（对应 langchain4j 的 CUSTOM） */
    CUSTOM(5);

    /** 落库数值编码 */
    private final int code;

    /**
     * 构造枚举：绑定该消息类型的落库编码
     *
     * @param code 落库数值编码
     */
    ChatMessageTypeCode(int code) {
        this.code = code;
    }

    /**
     * 取落库编码
     *
     * @return 该消息类型在 message_type 列中的数值
     */
    public int code() {
        return code;
    }

    /**
     * 把 langchain4j 的消息类型转成落库编码：本枚举常量名与框架枚举名完全一致，
     * 框架将来新增未知类型时这里直接抛错，避免静默落一个错误编码
     *
     * @param type langchain4j 的消息类型，不允许为空
     * @return 对应的落库数值编码
     */
    public static int codeOf(ChatMessageType type) {
        return valueOf(type.name()).code();
    }
}
