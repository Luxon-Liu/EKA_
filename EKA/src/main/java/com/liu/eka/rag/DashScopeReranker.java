package com.liu.eka.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liu.eka.entity.rag.Candidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 重排序器：封装 DashScope text-rerank 原生端点的调用逻辑，对召回候选按查询语义打分取 Top N，
 * 作为 RAG 混合检索的精排环节，供 RagSearchTool 编排调用
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Slf4j
@Component
public class DashScopeReranker {

    /** DashScope text-rerank 原生端点（qwen3-vl-rerank 不支持 OpenAI 兼容模式） */
    @Value("${rag.rerank.base-url}")
    private String baseUrl;

    /** 重排序模型名 */
    @Value("${rag.rerank.model}")
    private String model;

    /** DashScope API Key（环境变量优先） */
    @Value("${rag.rerank.api-key}")
    private String apiKey;

    /** 重排序返回条数 Top N */
    @Value("${rag.rerank.top-n:10}")
    private int topN;

    /** JSON 解析器（线程安全，全类复用），用于构造与解析重排请求体 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 重排序 HTTP 客户端（无状态可复用），连接超时 3 秒，作为底层兜底（工具层连接预算更短） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 调用重排序模型对候选打分：按 relevance_score 降序取 Top N
     *
     * @param query      重排序的查询语义（优先 originalQuery）
     * @param candidates 去重后的候选列表
     * @return 重排序后的 Top N 候选，按相关度降序
     * @throws IOException          请求体序列化或响应读取异常
     * @throws InterruptedException HTTP 请求被中断时抛出
     */
    public List<Candidate> rerank(String query, List<Candidate> candidates)
            throws IOException, InterruptedException {
        // 步骤 1：候选为空时直接返回空结果，避免发起无意义的重排请求
        if (candidates.isEmpty()) {
            log.info("候选为空，跳过重排序");
            return List.of();
        }

        // 步骤 2：构造 DashScope text-rerank 请求体：model + input(query/documents) + parameters(top_n)
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        // 纯文本 query 按 {"text": ...} 对象格式传入
        input.putObject("query").put("text", query);
        ArrayNode documents = input.putArray("documents");
        candidates.forEach(c -> documents.addObject().put("text", c.getText()));
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("top_n", topN);
        // 原文由本地候选列表映射回填，无需接口回传，减少传输开销
        parameters.put("return_documents", false);

        // 步骤 3：发起 HTTP 调用，非 200 视为失败并携带响应体抛出；
        //         请求超时 3s 作为底层兜底，略大于工具层单次超时（2s），正常应由工具层先掐断
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();
        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() != 200) {
            throw new IllegalStateException("重排序调用失败 HTTP " + httpResponse.statusCode()
                    + ": " + httpResponse.body());
        }

        // 步骤 4：按返回的 index 映射回本地候选，用 relevance_score 覆盖召回阶段分数后组装 Top N
        JsonNode results = mapper.readTree(httpResponse.body()).path("output").path("results");
        List<Candidate> top = new ArrayList<>();
        for (JsonNode result : results) {
            Candidate candidate = candidates.get(result.path("index").asInt());
            candidate.setScore(result.path("relevance_score").asDouble());
            top.add(candidate);
        }
        return top;
    }
}
