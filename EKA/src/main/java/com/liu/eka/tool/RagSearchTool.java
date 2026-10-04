package com.liu.eka.tool;

import com.liu.eka.entity.rag.ArchiveFilter;
import com.liu.eka.entity.rag.Candidate;
import com.liu.eka.rag.DashScopeReranker;
import com.liu.eka.rag.EsRagKeywordSearcher;
import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 混合检索工具（Agent 检索知识库的工具之一）：
 * 向量检索 + ES 精准检索两路召回 -> 按 chunk 业务 id 去重合并 -> 重排序模型打分取 Top N。
 * 入参：originalQuery（Agent 根据用户提问与上下文理解总结出的原始查询，专供重排序）、
 * vectorQuery（向量检索语义查询）、esQuery（ES 精准检索关键词，允许为空跳过本路），
 * 以及可选的档案身份过滤条件 ArchiveFilter（项目/卷/件/归档年度精确过滤，留空字段不参与）。
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool implements EkaTool {

    /**
     * 工具注册顺序：RAG 检索优先，排第一位展示给模型
     *
     * @return 顺序号 1
     */
    @Override
    public int order() {
        return 1;
    }

    /**
     * 整个检索工具的总超时：以用户可接受的等待时长为硬上限，内层向量化、向量检索、
     * 关键词检索、重排四步的单次超时与重试都在此预算内尽力而为，超出即兜断
     *
     * @return 总超时时长 6 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(6);
    }

    /** 向量化模型（DashScope text-embedding-v3，1024 维），用于查询词向量化 */
    private final EmbeddingModel embeddingModel;

    /** ES 向量存储（langchain4j 封装），负责向量路近邻检索 */
    private final ElasticsearchEmbeddingStore embeddingStore;

    /** ES 精准检索器（util 包独立组件），负责关键词召回路 */
    private final EsRagKeywordSearcher esRagKeywordSearcher;

    /** 重排序器（util 包独立组件），负责对合并后的候选做相关度精排 */
    private final DashScopeReranker dashScopeReranker;

    /** 工具调用统一韧性执行器：为向量化、向量检索、关键词检索、重排各自加单次超时与重试 */
    private final ToolGuard toolGuard;

    /** 向量召回数量：候选池要比重排目标大，给重排序留足挑选空间 */
    @Value("${rag.search.vector-top-k}")
    private int vectorTopK;

    /**
     * 混合检索主流程（langchain4j Tool）：两路召回 -> 去重合并 -> 重排序 -> 格式化为面向 LLM 的文本，
     * 由 Agent 框架通过 AiServices 装配调用，工具与参数的语义说明以 @Tool/@P 注解为准。
     * filter 会被下推到两路召回的查询阶段而非召回后内存过滤：向量路翻译为 langchain4j Filter 做 kNN 预过滤，
     * 关键词路翻译为 ES bool 查询的 filter 数组，两者均等价于 SQL 的 WHERE 语义，召回即已收敛
     *
     * @param originalQuery Agent 根据用户提问与上下文总结出的原始查询语义，专供重排序；为空时按向量查询、关键词查询依次兜底
     * @param vectorQuery   向量检索的语义查询词，允许为空（跳过向量路）
     * @param esQuery       ES 精准检索的关键词查询，允许为空（跳过关键词路）
     * @param filter        档案身份过滤条件（项目/卷/件/归档年度精确等值），可为 null 或全空即不过滤
     * @return 面向 LLM 的检索结果文本（逐条含相关度/来源/档案身份/正文），无命中时返回提示语
     * @throws IllegalArgumentException 两路查询词全空时抛出
     */
    @Tool("""
    文档混合检索工具（检索知识库中的档案原文与政策内容）。
    通过语义检索与关键词检索进行多路召回，并对召回结果进行重排序，
    返回与当前问题最相关的文档内容。
    【职责范围】本工具负责回答需要原文佐证的内容类问题：档案原文、政策依据、条款表述、
    办法/方案的具体内容（如“有哪些政策”“补偿标准是多少”“文件怎么规定的”“什么流程”）。
    调用本工具时，vectorQuery 和 esQuery 至少提供一个。
    当需要限定查询范围时，可提供 filter 档案过滤条件（按归属项目/卷/件/归档年度精确过滤，
    四者均可不填，留空的维度不限制；已知用户所属项目时可优先用 filter.projectName 圈定范围）。
    """)
    public String search(
            @P(value = "用于结果重排序的查询语义", required = true) String originalQuery,
            @P(value = "向量语义检索的查询语句", required = false) String vectorQuery,
            @P(value = "ES 关键词精准检索词", required = false) String esQuery,
            @P(value = "档案过滤条件（项目/卷/件/归档年度，可空对象即不过滤）", required = false) ArchiveFilter filter) {
        // 步骤 1：参数校验——两路召回至少要有一路有查询，全空则无法召回任何候选
        boolean hasVector = vectorQuery != null && !vectorQuery.isBlank();
        boolean hasKeyword = esQuery != null && !esQuery.isBlank();
        if (!hasVector && !hasKeyword) {
            throw new IllegalArgumentException("vectorQuery 与 esQuery 至少需要提供一个");
        }

        // 步骤 2：向量检索召回语义相关候选，档案条件下推到 kNN 预过滤（query 为空则跳过本路）
        List<Candidate> vectorHits = hasVector ? vectorSearch(vectorQuery, filter) : List.of();

        // 步骤 3：ES 精准检索召回关键词命中候选，档案条件下推到 bool filter（query 为空则跳过本路，降级为单路召回 + 重排）
        List<Candidate> keywordHits = hasKeyword
                ? toolGuard.run("rag.keyword", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(1500, 2, 3500),
                        () -> esRagKeywordSearcher.search(esQuery, filter))
                : List.of();

        // 步骤 4：两路候选合并去重（向量命中优先保序）
        List<Candidate> candidates = deduplicate(vectorHits, keywordHits);

        // 步骤 5：送重排序器打分（独立超时与重试），输出最终 Top N
        List<Candidate> top = toolGuard.run("rag.rerank", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(2000, 2, 4500),
                () -> dashScopeReranker.rerank(originalQuery, candidates));

        // 步骤 6：组装切块上下文
        return formatForLlm(top);
    }

    /**
     * 向量检索召回：把查询词向量化后在 ES 向量库做近邻检索；档案条件非空时翻译为 langchain4j Filter，
     * 由 ES kNN 查询预过滤（先按条件圈定候选再做近似近邻，语义等价 SQL 的 WHERE + ANN）
     *
     * @param vectorQuery 语义查询词，调用方保证非空
     * @param filter      档案过滤条件（项目/卷/件/归档年度），可为 null 或全空即不过滤
     * @return 按向量相似度降序的候选列表
     */
    private List<Candidate> vectorSearch(String vectorQuery, ArchiveFilter filter) {
        // 步骤 1：查询词向量化（独立超时与重试）
        Embedding queryEmbedding = toolGuard.run("rag.embedding", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(1500, 2, 3500),
                () -> embeddingModel.embed(vectorQuery).content());

        // 步骤 2：构造搜索请求——档案条件非空时作为 kNN 预过滤下推，先过滤再近邻检索
        Filter vectorFilter = toVectorFilter(filter);
        EmbeddingSearchRequest request;
        if (vectorFilter != null) {
            request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(vectorTopK)
                    .filter(vectorFilter)
                    .build();
        } else {
            request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(vectorTopK)
                    .build();
        }

        // 步骤 3：向量库近邻检索（独立超时与重试）
        List<EmbeddingMatch<TextSegment>> matches = toolGuard.run("rag.vector", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(2000, 2, 4500),
                () -> embeddingStore.search(request).matches());

        // 步骤 4：转为统一候选结构，origin 标记为向量检索，携带向量相似度分数
        List<Candidate> hits = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            hits.add(Candidate.builder()
                    .id(segment.metadata().getString("id"))
                    .text(segment.text())
                    .metadata(segment.metadata())
                    .origin("向量检索")
                    .score(match.score())
                    .build());
        }
        return hits;
    }

    /**
     * 把档案过滤条件翻译成 langchain4j Filter（供向量路 kNN 预过滤使用）：
     * 字符串维度用 isEqualTo 精确匹配（ES 端由 langchain4j 自动落到各字段 .keyword 子字段），
     * 归档年度先解析为 long 再比较（form_year 在 ES 是长整型字段，数值比较才能命中）；
     * 多个维度用 and 折叠为合取表达式；无条件时返回 null
     *
     * @param filter 档案过滤条件，可为 null
     * @return langchain4j Filter，无条件时为 null（表示不过滤）
     */
    private Filter toVectorFilter(ArchiveFilter filter) {
        // 无任何已填写维度（null 或全空）：不生成过滤条件
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        // 逐一为已填写维度生成等值条件：项目/卷/件按字符串、归档年度按数字年份
        List<Filter> conditions = new ArrayList<>(4);
        if (ArchiveFilter.isFilled(filter.getProjectName())) {
            conditions.add(metadataKey("project_name").isEqualTo(filter.getProjectName()));
        }
        if (ArchiveFilter.isFilled(filter.getVolumeName())) {
            conditions.add(metadataKey("volume_name").isEqualTo(filter.getVolumeName()));
        }
        if (ArchiveFilter.isFilled(filter.getItemName())) {
            conditions.add(metadataKey("item_name").isEqualTo(filter.getItemName()));
        }
        if (ArchiveFilter.isFilled(filter.getFormYear())) {
            conditions.add(metadataKey("form_year").isEqualTo(filter.formYearNumber()));
        }
        // 单条件直接返回；多条件用 and 依次折叠为合取表达式（语义为全部满足）
        Filter combined = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            combined = combined.and(conditions.get(i));
        }
        return combined;
    }

    /**
     * 两路候选合并去重：以候选 id 为唯一键，LinkedHashMap 保持向量命中的相似度顺序，向量命中优先保留
     *
     * @param vectorHits  向量检索候选（已按相似度降序）
     * @param keywordHits 关键词检索候选（已按 BM25 分数降序）
     * @return 去重后的候选列表，先向量命中后关键词命中
     */
    private List<Candidate> deduplicate(List<Candidate> vectorHits, List<Candidate> keywordHits) {
        // 用 LinkedHashMap 按插入顺序去重：同一 id 后到者丢弃，保证向量命中优先
        LinkedHashMap<String, Candidate> merged = new LinkedHashMap<>();
        vectorHits.forEach(c -> merged.putIfAbsent(c.getId(), c));
        keywordHits.forEach(c -> merged.putIfAbsent(c.getId(), c));
        return new ArrayList<>(merged.values());
    }

    /**
     * 把重排后的候选列表格式化为面向 LLM 的文本：逐条输出相关度、召回来源、档案身份信息与完整正文，
     * 供大模型直接阅读并据此回答用户问题
     *
     * @param candidates 重排序后的 Top N 候选
     * @return 格式化后的检索结果文本，无命中时返回提示语
     */
    private String formatForLlm(List<Candidate> candidates) {
        // 空结果提示：明确告诉 LLM 本次检索无命中
        if (candidates.isEmpty()) {
            return "未检索到相关内容";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            Metadata md = c.getMetadata();
            Integer formYear = md.getInteger("form_year");

            // 头行：序号 + 重排相关度 + 召回来源
            sb.append("[第").append(i + 1).append("条 | 相关度 ")
                    .append(String.format("%.4f", c.getScore()))
                    .append(" | 来源: ").append(c.getOrigin()).append("]\n");
            // 块结构行：业务 id + 页码 + 章节
            sb.append("块ID: ").append(c.getId())
                    .append(" | 页码: ").append(md.getString("page"))
                    .append(" | 章节: ").append(md.getString("section")).append('\n');
            // 身份行：项目/卷/件/归档年度（form_year 为数字字段缺失显 -，其余字符串字段缺失显 null）
            sb.append("项目: ").append(md.getString("project_name"))
                    .append(" | 卷: ").append(md.getString("volume_name"))
                    .append(" | 件: ").append(md.getString("item_name"))
                    .append(" | 年度: ").append(formYear != null ? formYear : "-").append('\n');
            // 正文整段
            sb.append("正文: ").append(c.getText()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
