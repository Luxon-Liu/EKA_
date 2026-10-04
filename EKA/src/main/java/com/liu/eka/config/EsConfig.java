package com.liu.eka.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端的独立 Bean 配置：仅负责低级 REST 客户端的构建与连接参数，
 * 向量存储等上层 Bean 由 EmbeddingConfig 基于本类提供的 RestClient 组装
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Configuration
public class EsConfig {

    /** ES 地址 */
    @Value("${es.url}")
    private String esUrl;

    /** ES 连接超时秒数：大于 ES 相关步骤的单次超时（向量/关键词检索 2 秒），作为底层兜底 */
    @Value("${es.connect-timeout-seconds:3}")
    private int connectTimeoutSeconds;

    /** ES 读写超时秒数：略大于 ES 相关步骤的单次超时（向量/关键词检索 2 秒），防止请求永久挂起占住线程 */
    @Value("${es.socket-timeout-seconds:3}")
    private int socketTimeoutSeconds;

    /**
     * 构建 ES 低级 REST 客户端（全局单例，向量存储与后续检索共用）
     *
     * @return ES RestClient 实例
     */
    @Bean
    public RestClient esRestClient() {
        // 步骤 1：按配置的 ES 地址构建低级客户端，并显式设置连接与读写超时——
        //         裸 RestClient 默认无 socket 超时，ES 无响应时请求会永久挂起
        return RestClient.builder(HttpHost.create(esUrl))
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(connectTimeoutSeconds * 1000)
                        .setSocketTimeout(socketTimeoutSeconds * 1000))
                .build();
    }
}
