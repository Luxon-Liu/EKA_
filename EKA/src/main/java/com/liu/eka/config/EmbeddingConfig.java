package com.liu.eka.config;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量模型与向量存储的 Bean 配置：向量化参数由 rag.embedding.* 注入，
 * ES 连接依赖 EsConfig 提供的全局 RestClient，索引名由 es.index 注入
 *
 * @author Luxon
 * @date 2026/08/26
 */
@Configuration
public class EmbeddingConfig {

    /** DashScope OpenAI 兼容地址 */
    @Value("${rag.embedding.base-url}")
    private String baseUrl;

    /** DashScope API Key（yml 中直接配置） */
    @Value("${rag.embedding.api-key}")
    private String apiKey;

    /** 向量模型名称 */
    @Value("${rag.embedding.model}")
    private String model;

    /** ES 索引名 */
    @Value("${es.index}")
    private String index;

    /**
     * 构建向量模型
     *
     * @return 向量模型实例
     */
    @Bean
    public OpenAiEmbeddingModel embeddingModel() {
        // 步骤 1：基于 DashScope 兼容模式构建 text-embedding-v3 模型
        // 步骤 2：单批最多 10 条文本（DashScope 限制 batch size <= 10，langchain4j 默认 2048 会报 400）
        // 步骤 3：重试次数归零——向量化的单次超时与重试统一交给工具层 ToolGuard，避免与模型自带重试叠加放大
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model).dimensions(1024)
                .maxSegmentsPerBatch(10)
                .maxRetries(0)
                .build();
    }

    /**
     * 构建 ES 向量存储
     *
     * @param restClient ES 客户端，由 EsConfig 提供并经 Spring 注入
     * @return 向量存储实例
     */
    @Bean
    public ElasticsearchEmbeddingStore embeddingStore(RestClient restClient) {
        // 步骤 1：复用全局 RestClient 与索引名构建向量存储
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(index).build();
    }
}
