package com.liu.eka.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liu.eka.entity.rag.ArchiveFilter;
import com.liu.eka.entity.rag.Candidate;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 精准检索器：对索引目标字段做 BM25 match 全文检索，把命中文档还原为带完整元数据的候选列表，
 * 作为 RAG 混合检索的关键词召回路，供 RagSearchTool 编排调用
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Component
@RequiredArgsConstructor
public class EsRagKeywordSearcher {

    /** ES 低级 REST 客户端（EsConfig 提供的全局单例），精准检索复用，不再临时新建 */
    private final RestClient esRestClient;

    /** ES 精准检索召回数量：候选池要比重排目标大，给重排序留足挑选空间 */
    @Value("${rag.search.keyword-top-k:20}")
    private int topK;

    /** BM25 match 全文检索的目标字段（正文块字段，固定写死） */
    private static final String FIELD = "text";

    /** 向量与精准检索共用的目标索引名（与 es.index 保持同一来源） */
    @Value("${es.index}")
    private String esIndex;

    /** JSON 解析器（线程安全，全类复用），用于构造与解析 ES 查询体 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行 ES 精准检索（便捷重载）：不带档案过滤条件，等价于调用带 null 过滤条件的检索方法
     *
     * @param esQuery 关键词查询串，调用方保证非空
     * @return 按 BM25 相关度降序的候选列表
     * @throws IOException ES 请求发送或响应读取失败时抛出
     */
    public List<Candidate> search(String esQuery) throws IOException {
        return search(esQuery, null);
    }

    /**
     * 执行 ES 精准检索：对配置的目标字段做 BM25 match 全文检索，并在同一 bool 查询的 filter 数组
     * 中叠加档案等值条件（等价于 SQL 的 WHERE ... AND ...；filter 数组不参与打分且被 ES 缓存）。
     * 字符串字段匹配其 .keyword 子字段，归档年度解析为数字匹配 form_year 长整型字段
     *
     * @param esQuery 关键词查询串，调用方保证非空
     * @param filter  档案过滤条件（项目/卷/件/归档年度），可为 null 或全空即不过滤
     * @return 按 BM25 相关度降序的候选列表
     * @throws IOException ES 请求发送或响应读取失败时抛出
     */
    public List<Candidate> search(String esQuery, ArchiveFilter filter) throws IOException {
        // 步骤 1：构造 bool 查询请求体——must 承载 match 全文检索，filter 数组承载档案等值条件
        ObjectNode body = mapper.createObjectNode();
        body.put("size", topK);
        ObjectNode boolQuery = body.putObject("query").putObject("bool");
        boolQuery.putObject("must").putObject("match").put(FIELD, esQuery);

        // 步骤 2：档案条件非空时逐字段追加 term（字符串查 .keyword，年度数字查 form_year）
        if (filter != null && !filter.isEmpty()) {
            ArrayNode filters = boolQuery.putArray("filter");
            if (ArchiveFilter.isFilled(filter.getProjectName())) {
                filters.addObject().putObject("term")
                        .put("metadata.project_name.keyword", filter.getProjectName());
            }
            if (ArchiveFilter.isFilled(filter.getVolumeName())) {
                filters.addObject().putObject("term")
                        .put("metadata.volume_name.keyword", filter.getVolumeName());
            }
            if (ArchiveFilter.isFilled(filter.getItemName())) {
                filters.addObject().putObject("term")
                        .put("metadata.item_name.keyword", filter.getItemName());
            }
            if (ArchiveFilter.isFilled(filter.getFormYear())) {
                filters.addObject().putObject("term")
                        .put("metadata.form_year", filter.formYearNumber());
            }
        }
        Request request = new Request("POST", "/" + esIndex + "/_search");
        request.setJsonEntity(mapper.writeValueAsString(body));

        // 步骤 3：复用全局 ES 客户端执行检索并解析命中列表（外层 hits 为统计对象，内层 hits 为命中数组）
        Response response = esRestClient.performRequest(request);
        JsonNode hits = mapper.readTree(EntityUtils.toString(response.getEntity()))
                .path("hits").path("hits");

        // 步骤 4：把 _source 里的 metadata 对象逐字段复制为 langchain4j Metadata，统一候选结构
        List<Candidate> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            JsonNode metadataJson = source.path("metadata");
            Metadata metadata = new Metadata();
            metadataJson.properties().forEach(e -> metadata.put(e.getKey(), e.getValue().asText()));
            results.add(Candidate.builder()
                    .id(metadataJson.path("id").asText())
                    .text(source.path("text").asText())
                    .metadata(metadata)
                    .origin("精准检索")
                    .score(hit.path("_score").asDouble())
                    .build());
        }
        return results;
    }
}
