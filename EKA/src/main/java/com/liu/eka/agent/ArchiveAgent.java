package com.liu.eka.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 档案问答智能体接口：由 AiServices 生成代理实现，承载 agent loop 的提示词与会话路由；
 * 实际的模型、工具、记忆、监听器由 ArchiveAgentServiceImpl 在构建时装配，本接口只定语义
 *
 * @author Luxon
 * @date 2026/09/04
 */
public interface ArchiveAgent {

    /** 档案问答系统提示词 */
    String SYSTEM_PROMPT = """
            你是「政务档案利用与分析智能体」，面向政务档案工作人员，帮助其查阅档案原文、
            理解政策依据、统计分析档案数据，并在此基础上完成公文、材料等文档的撰写与加工。

            【回答要求】
            1. 只依据你实际获取到的内容回答，信息不足就如实说明未能找到，不要编造。
            2. 回答聚焦档案业务本身，语言自然、面向业务人员，不透露任何系统内部实现细节。

            【禁止暴露的内部信息】
            以下内容一律不得出现在你给用户的回答中：
            - 不得提及知识库、数据库、检索、向量、召回、重排序、工具、字段名、表名等任何系统内部概念或实现字眼；
            - 不得出现任何内部标识，如文件 ID、档案 ID、块 ID、记录 ID 等编号；
            - 不得描述你调用了什么功能或经过了哪些步骤来得到答案。
            若用户追问信息来源，只以业务口径回答（如「根据XXX档案原文」「依据XXX文件规定」），
            而不要提及上述内部信息。
            """;

    /**
     * 流式执行一轮对话
     * 调用方订阅 onPartialResponse 等回调后必须调 start() 启动
     *
     * @param memoryId 记忆 ID，用于路由到对应会话的记忆窗口
     * @param message   用户本轮问题
     * @return 文本增量流，订阅后调 start() 启动
     */
    @SystemMessage(SYSTEM_PROMPT)
    TokenStream chatStream(@MemoryId Object memoryId, @UserMessage String message);
}
