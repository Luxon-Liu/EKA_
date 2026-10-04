package com.liu.eka.agent;

import com.liu.eka.entity.chat.AgentEvent;
import com.liu.eka.tool.EkaTool;
import com.liu.eka.tool.resilience.ResiliencePools;
import com.liu.eka.tool.resilience.ToolTimeoutExecutor;
import com.liu.eka.util.Texts;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 档案智能体代理工厂：把 AiServices 组装（对话模型 + 记忆提供器 + 工具扫描注册）收拢到一处，
 * service 层只管拿代理跑 loop，不碰框架拼装细节；
 * 记忆实现与压缩策略装配在 ChatMemoryConfig，本工厂只注入现成的提供器。
 * 每轮现场构建新代理的原因：监听器闭包绑定本轮的调用链收集器，避免并发请求间串扰
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveAgentFactory {

    /** 工具参数最多记录字符数，超长截断只保留前部 */
    private static final int MAX_ARGUMENTS_LENGTH = 500;

    /** 工具结果最多记录字符数，超长截断只保留前部 */
    private static final int MAX_RESULT_LENGTH = 1000;

    /** 流式对话模型（兜底包装：DeepSeek 失败自动切通义千问；同步调用时框架内部阻塞收齐完整响应） */
    private final StreamingChatModel streamingChatModel;

    /** 会话记忆提供器（ChatMemoryConfig 装配：按 memoryId 建独立记忆实例，落 MySQL 存储） */
    private final ChatMemoryProvider chatMemoryProvider;

    /** 模型用量监听器：每次模型调用后把真实 input token 写回会话，供下一轮压缩判断作基线 */
    private final TokenUsageListener tokenUsageListener;

    /** 调用失败监听器：模型调用报错时清空基线，让下一轮压缩判断退回按视图估算 */
    private final TokenUsageResetListener tokenUsageResetListener;

    /**
     * Agent 工具全集：tool 包下所有 EkaTool 实现由 Spring 按类型批量注入，
     * 新增工具只需实现接口，无需改动本工厂
     */
    private final List<EkaTool> ekaTools;

    /** 工具韧性线程池：外层装饰器用它隔离执行整个工具方法以便计时总超时 */
    private final ResiliencePools resiliencePools;

    /**
     * 单轮对话允许的最大连续工具调用次数：超过即由 langchain4j 中断本轮并抛错，
     * 防止模型在检索/查库上无限刷调用撑爆上下文
     */
    @Value("${agent.max-tool-invocations}")
    private int maxToolInvocations;

    /**
     * 现场构建一轮对话的智能体代理：记忆按 memoryId 路由到 MySQL，
     * 工具调用前后经官方监听器转成事件交 eventSink（同步攒调用链，流式直推前端）。
     * 工具执行器逐个方法包装：DefaultToolExecutor 负责参数解析与调用，外层再套 ToolTimeoutExecutor
     * 限制「整个工具方法总超时」；内层每次外部调用的单次超时与重试由 ToolGuard 在编排层完成
     *
     * @param memoryId 记忆 ID（后端自增），用于记忆路由与日志关联，调用方保证非空
     * @param eventSink 事件出口，每次工具调用前后各发一帧
     * @return 当轮可用的智能体代理
     */
    public ArchiveAgent build(Long memoryId, BiConsumer<AgentEvent, Map<String, Object>> eventSink) {
        // 步骤 1：工具按 order 排序，顺序即模型可见的工具描述顺序
        List<EkaTool> sortedTools = ekaTools.stream()
                .sorted(Comparator.comparingInt(EkaTool::order))
                .toList();

        // 步骤 2：逐个 @Tool 方法注册受保护的执行器——
        //         参数异常包装与执行异常外抛必须开启，否则参数错误会被误判为执行错误、错误处理串味
        Map<ToolSpecification, ToolExecutor> toolExecutors = new LinkedHashMap<>();
        for (EkaTool tool : sortedTools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                ToolExecutor delegate = DefaultToolExecutor.builder()
                        .object(tool)
                        .originalMethod(method)
                        .methodToInvoke(method)
                        .wrapToolArgumentsExceptions(true)
                        .propagateToolExecutionExceptions(true)
                        .build();
                toolExecutors.put(specification, new ToolTimeoutExecutor(
                        method.getName(), delegate, tool.totalTimeout(), resiliencePools.outer()));
            }
        }

        // 步骤 3：现场构建代理，工具调用链经官方 before/after 监听器转事件（闭包绑定本轮出口），
        //         并注册用量监听器收集真实 token 基线；两个错误处理器均返回文本给模型，不炸断整轮
        return AiServices.builder(ArchiveAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .registerListener(tokenUsageListener)
                .registerListener(tokenUsageResetListener)
                .tools(toolExecutors)
                .maxSequentialToolsInvocations(maxToolInvocations)
                .toolArgumentsErrorHandler((error, context) -> ToolErrorHandlerResult.text(
                        "工具参数不合法：" + describe(error) + "。请修正参数后重新调用。"))
                .toolExecutionErrorHandler((error, context) -> ToolErrorHandlerResult.text(
                        "工具执行失败：" + describe(error)))
                .beforeToolExecution(before -> {
                    String arguments = Texts.abbreviate(before.request().arguments(), MAX_ARGUMENTS_LENGTH);
                    log.info("工具调用开始：memoryId={}，调用={}，工具={}，参数前 500 字={}",
                            memoryId, before.request().id(), before.request().name(), arguments);
                    eventSink.accept(AgentEvent.TOOL_CALL_REQUEST,
                            AgentEvent.toolRequestData(before.request().id(), before.request().name(), arguments));
                })
                .afterToolExecution(after -> {
                    String arguments = Texts.abbreviate(after.request().arguments(), MAX_ARGUMENTS_LENGTH);
                    String resultSummary = Texts.abbreviate(after.result(), MAX_RESULT_LENGTH);
                    log.info("工具调用结束：memoryId={}，调用={}，工具={}，结果前 200 字={}",
                            memoryId, after.request().id(), after.request().name(), Texts.abbreviate(after.result(), 200));
                    eventSink.accept(AgentEvent.TOOL_CALL_RESULT,
                            AgentEvent.toolResultData(after.request().id(), after.request().name(), arguments, resultSummary));
                })
                .build();
    }

    /**
     * 提取异常的一句话描述：优先用消息，无消息时退回类名，供工具错误文本回给模型
     *
     * @param error 异常
     * @return 简短描述
     */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
