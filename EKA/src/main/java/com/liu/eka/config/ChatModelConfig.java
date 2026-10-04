package com.liu.eka.config;

import com.liu.eka.model.FallbackChatModel;
import com.liu.eka.model.FallbackStreamingChatModel;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 对话模型的 Bean 配置：按厂商分组注册流式与阻塞模型，
 * 再各包一层兜底（Fallback）作为对外主 bean（标 @Primary）：
 * 主模型（DeepSeek）连接失败时静默切换兜底模型（通义千问），
 * 供 Agent 流式与标题生成透明使用，厂商波动对上层无感
 *
 * @author Luxon
 * @date 2026/09/09
 */
@Configuration
public class ChatModelConfig {

    /** DeepSeek 官方 OpenAI 兼容地址 */
    @Value("${llm.deepseek.base-url}")
    private String deepseekBaseUrl;

    /** DeepSeek 官方 API Key */
    @Value("${llm.deepseek.api-key}")
    private String deepseekApiKey;

    /** DeepSeek 对话模型名称 */
    @Value("${llm.deepseek.model}")
    private String deepseekModel;

    /** 通义千问 OpenAI 兼容地址（阿里云百炼） */
    @Value("${llm.qwen.base-url}")
    private String qwenBaseUrl;

    /** 通义千问 API Key（与 embedding/rerank 共用百炼钥匙） */
    @Value("${llm.qwen.api-key}")
    private String qwenApiKey;

    /** 通义千问对话模型名称 */
    @Value("${llm.qwen.model}")
    private String qwenModel;

    /**
     * 构建强制 HTTP/1.1 的 langchain4j JDK 客户端构建器：
     * DeepSeek 对 HTTP/2 长连接（尤其流式）会偶发 Connection reset，
     * 强制降级为 HTTP/1.1 可规避该问题；每次调用返回新实例，避免多个模型共享可变状态。
     * version 经由底层 HttpClient.Builder 生效，connect/read 超时由各模型层设置
     *
     * @return 独立的 HTTP/1.1 客户端构建器实例
     */
    private JdkHttpClientBuilder http11ClientBuilder() {
        HttpClient.Builder jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);
        return new JdkHttpClientBuilder().httpClientBuilder(jdk);
    }

    /**
     * 通用流式模型构建：统一强制 HTTP/1.1 规避连接重置，超时与模型参数按厂商传入
     *
     * @param baseUrl OpenAI 兼容接口地址
     * @param apiKey  厂商 API Key
     * @param model   模型名称
     * @param timeout 单轮流式总超时
     * @return 流式对话模型实例
     */
    private OpenAiStreamingChatModel buildStreaming(String baseUrl, String apiKey, String model, Duration timeout) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .httpClientBuilder(http11ClientBuilder())
                .timeout(timeout)
                .build();
    }

    /**
     * 通用阻塞模型构建：统一强制 HTTP/1.1，配置超时与最多重试次数降低失败率
     *
     * @param baseUrl    OpenAI 兼容接口地址
     * @param apiKey     厂商 API Key
     * @param model      模型名称
     * @param timeout    单次调用整体超时
     * @param maxRetries 最多重试次数
     * @return 阻塞对话模型实例
     */
    private OpenAiChatModel buildChat(String baseUrl, String apiKey, String model, Duration timeout, int maxRetries) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .httpClientBuilder(http11ClientBuilder())
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }

    /**
     * 主力流式对话模型（DeepSeek）：单轮流式总时长放宽到 5 分钟，
     * 避免长回答被默认 60s 总时限掐断
     *
     * @return DeepSeek 流式对话模型实例
     */
    @Bean
    public OpenAiStreamingChatModel deepseekStreamingChatModel() {
        return buildStreaming(deepseekBaseUrl, deepseekApiKey, deepseekModel, Duration.ofMinutes(5));
    }

    /**
     * 主力阻塞对话模型（DeepSeek）：同参数同模型，用于标题生成这类一次性调用，
     * 配置 60s 整体超时与最多 3 次重试降低失败率
     *
     * @return DeepSeek 阻塞对话模型实例
     */
    @Bean
    public OpenAiChatModel deepseekChatModel() {
        return buildChat(deepseekBaseUrl, deepseekApiKey, deepseekModel, Duration.ofSeconds(60), 3);
    }

    /**
     * 兜底流式对话模型（通义千问）：连接更稳、响应更快，供兜底包装在 DeepSeek 失败时接管；
     * 响应快故总超时收紧到 3 分钟，避免故障场景下前端长时间空等
     *
     * @return 通义千问流式对话模型实例
     */
    @Bean
    public OpenAiStreamingChatModel qwenStreamingChatModel() {
        return buildStreaming(qwenBaseUrl, qwenApiKey, qwenModel, Duration.ofMinutes(3));
    }

    /**
     * 兜底阻塞对话模型（通义千问）：用于一次性短调用，配置 60s 整体超时与最多 3 次重试
     *
     * @return 通义千问阻塞对话模型实例
     */
    @Bean
    public OpenAiChatModel qwenChatModel() {
        return buildChat(qwenBaseUrl, qwenApiKey, qwenModel, Duration.ofSeconds(60), 3);
    }

    /**
     * 对外流式对话模型（兜底包装）：主备模型都注册进包装，
     * DeepSeek 连接类失败且未输出内容时自动切通义千问重跑整轮。
     * 标 @Primary，Agent 流式按类型注入拿到的是此包装，厂商切换对其透明
     *
     * @param deepseekStreamingChatModel 主力流式模型（DeepSeek）
     * @param qwenStreamingChatModel     兜底流式模型（通义千问）
     * @return 流式兜底包装实例
     */
    @Bean
    @Primary
    public StreamingChatModel streamingChatModel(
            OpenAiStreamingChatModel deepseekStreamingChatModel,
            OpenAiStreamingChatModel qwenStreamingChatModel) {
        return new FallbackStreamingChatModel(deepseekStreamingChatModel, qwenStreamingChatModel);
    }

    /**
     * 对外阻塞对话模型（兜底包装）：主备模型都注册进包装，
     * DeepSeek 调用可重试失败时自动切通义千问重试。
     * 标 @Primary，标题生成按类型注入拿到的是此包装
     *
     * @param deepseekChatModel 主力阻塞模型（DeepSeek）
     * @param qwenChatModel     兜底阻塞模型（通义千问）
     * @return 阻塞兜底包装实例
     */
    @Bean
    @Primary
    public ChatModel chatModel(
            OpenAiChatModel deepseekChatModel,
            OpenAiChatModel qwenChatModel) {
        return new FallbackChatModel(deepseekChatModel, qwenChatModel);
    }
}