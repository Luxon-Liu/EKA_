package com.liu.eka.config;

import ai.docling.serve.api.DoclingServeApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Docling Serve 客户端的 Bean 配置：服务地址由 application.yml 的 rag.docling.base-url 注入，
 * 超时参数沿用验证过的取值（覆盖首次模型加载与异步任务排队耗时）
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Configuration
public class DoclingConfig {

    /** Docling Serve 服务地址 */
    @Value("${rag.docling.base-url}")
    private String baseUrl;

    /**
     * 构建文档解析客户端（无状态，可全局复用）
     *
     * @return Docling Serve 客户端实例
     */
    @Bean
    public DoclingServeApi doclingServeApi() {
        // 步骤 1：按验证过的超时参数构建客户端——
        // readTimeout 5 分钟覆盖同步请求等待服务端加载模型（60~90s 以上）；
        // asyncTimeout 15 分钟是异步任务从提交到出结果的总等待上限（排队 + 模型加载）；
        // asyncPollInterval 2 秒是异步任务状态的轮询间隔
        return DoclingServeApi.builder()
                .baseUrl(baseUrl)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(5))
                .asyncTimeout(Duration.ofMinutes(15))
                .asyncPollInterval(Duration.ofSeconds(2))
                .build();
    }
}
