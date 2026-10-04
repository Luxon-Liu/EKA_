package com.liu.eka.demo;

import ai.docling.core.DoclingDocument;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import com.liu.eka.rag.DoclingHybridChunker;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 数据灌入流程演示：docling 解析文档 -> HybridChunker 语义切块 -> 向量化 -> 存入 ES -> 语义检索验证
 *
 * @author Luxon
 * @date 2026/08/27
 */
@SpringBootTest
class RagIngestTest {
    /** 本地 Docling Serve 服务地址（由 pip 安装的 docling-serve 在 5001 端口提供） */
    private static final String DOCLING_URL = "http://127.0.0.1:5001";

    /** 待解析的测试 PDF 路径：工程根目录上一级的 File 目录 */
    private static final Path PDF_PATH = Path.of("..", "File", "广州市黄埔区智慧城市信息枢纽中心竣工验收报告.pdf");

    /** ES 服务地址：本机 9200 端口（与 EmbeddingEsConfig 保持一致） */
    private static final String ES_URL = "http://127.0.0.1:9200";

    /** 向量索引名：与 EmbeddingEsConfig 中的 INDEX 保持一致 */
    private static final String INDEX = "eka-rag";

    /** 文档解析客户端（无状态，可复用）：
     *  readTimeout 5 分钟：首次同步请求需等待服务端加载模型（60~90s 以上）；
     *  asyncTimeout 15 分钟：异步任务从提交到出结果的总等待上限（覆盖排队 + 模型加载）；
     *  asyncPollInterval 2 秒：异步任务状态轮询间隔 */
    private final DoclingServeApi doclingApi = DoclingServeApi.builder()
            .baseUrl(DOCLING_URL)
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofMinutes(5))
            .asyncTimeout(Duration.ofMinutes(15))
            .asyncPollInterval(Duration.ofSeconds(2))
            .build();

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ElasticsearchEmbeddingStore store;

    /** HybridChunker 切块器（docling HybridChunker 算法的 Java 实现），由 Spring 容器注入 */
    @Autowired
    private DoclingHybridChunker hybridChunker;

    /**
     * 完整灌入流程：解析 PDF 为结构树、HybridChunker 语义切块、批量向量化后写入 ES
     */
    @Test
    void ingest() throws Exception {
        // 步骤 1：读取本地 PDF 并包装为 Base64 文件源（docling-java 通过 HTTP 上传给 Docling Serve 解析）
        byte[] pdfBytes = Files.readAllBytes(PDF_PATH);
        FileSource source = FileSource.builder()
                .filename(PDF_PATH.getFileName().toString())
                .base64String(Base64.getEncoder().encodeToString(pdfBytes))
                .build();

        // 步骤 2：调用 Docling Serve 做结构识别，输出 DoclingDocument 结构树（版面分析 + OCR 由服务端完成）
        ConvertDocumentRequest request = ConvertDocumentRequest.builder()
                .source(source)
                .options(ConvertDocumentOptions.builder()
                        .toFormat(OutputFormat.JSON)
                        .build())
                .target(InBodyTarget.builder().build())
                .build();
        InBodyConvertDocumentResponse response =
                (InBodyConvertDocumentResponse) doclingApi.convertSource(request);

        // 步骤 3：检查解析错误并取出结构树
        if (!response.getErrors().isEmpty()) {
            response.getErrors().forEach(e ->
                    System.err.println("解析异常: " + e.getComponentType() + " - " + e.getErrorMessage()));
            throw new IllegalStateException("Docling 解析失败");
        }
        DoclingDocument doc = response.getDocument().getJsonContent();

        // 步骤 4：HybridChunker 语义切块（结构层级 + token 精修 + 相邻合并），并转成带元数据的 Chunk
        List<DoclingHybridChunker.HybridChunk> hybridChunks = hybridChunker.chunk(doc);
        String title = stripExtension(doc.getOrigin().getFilename());
        List<TextSegment> chunks = toTextSegments(hybridChunks, title);
        System.out.println("=== HybridChunker 切分出 Chunk 数量: " + chunks.size() + " ===");

        // 步骤 5：批量向量化所有 Chunk（阿里 DashScope text-embedding-v3，1024 维）
        List<Embedding> embeddings = embeddingModel.embedAll(chunks).content();

        // 步骤 6：每个 Chunk 用物理顺序编号作为唯一 ID 后写入 ES（重复执行会叠加不同 ID，需先 clearIndex）
        List<String> ids = new ArrayList<>(chunks.size());
        for (TextSegment chunk : chunks) {
            ids.add(chunk.metadata().getString("id"));
        }
        store.addAll(ids, embeddings, chunks);
        System.out.println("=== 已写入 ES 共 " + chunks.size() + " 条 Chunk ===");
    }

    /**
     * 批量解析演示（文档级独立流水线）：每份 PDF 各自走完「异步解析 -> HybridChunker 切块 -> 逐块预览打印」，
     * 单个文档失败只记入失败清单、不影响其他文档（只打印结果，不入库、不向量化），
     * 最后汇总本次批量处理的成功与失败明细，作为将来批量入库接口失败语义的雏形
     *
     * @throws Exception 等待流水线结束被中断时抛出
     */
    @Test
    void parallelParseAndChunk() throws Exception {
        // 步骤 1：写死 3 份测试文件路径（main-cbcz-02 的三个年份目录各一份）
        List<Path> files = List.of(
                Path.of("..", "File", "EK_AR_MAIN", "main-cbcz-02", "2023", "12", "车陂村城中村改造项目复建安置区一期施工许可报审表.pdf"),
                Path.of("..", "File", "EK_AR_MAIN", "main-cbcz-02", "2024", "05", "车陂村城中村改造项目安置区桩基工程质量检测报告.pdf"),
                Path.of("..", "File", "EK_AR_MAIN", "main-cbcz-02", "2025", "09", "车陂村城中村改造项目安置区一期主体结构验收记录.pdf"));

        // 步骤 2：逐个提交文档级流水线：每个 future 就是「一份文档从提交到出结果」的完整生命周期，
        // 单个文档的任何失败都会被捕获为失败项，不会中断其他文档的处理
        List<CompletableFuture<DocResult>> futures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            futures.add(processOne(i + 1, files.get(i)));
        }

        // 步骤 3：等待全部流水线结束（失败已在内部转为失败项，此处不会抛出业务异常）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 步骤 4：汇总成败：按是否携带错误信息分成成功/失败两组
        List<DocResult> results = new ArrayList<>();
        for (CompletableFuture<DocResult> future : futures) {
            results.add(future.join());
        }
        List<DocResult> success = results.stream().filter(r -> r.error() == null).toList();
        List<DocResult> failed = results.stream().filter(r -> r.error() != null).toList();

        // 步骤 5：打印批量处理报告：先成功明细，后失败明细及原因（将来接口层可据此返回给调用方决定重试哪些文件）
        System.out.println("========== 批量处理汇总：成功 " + success.size() + " / 失败 " + failed.size() + " ==========");
        for (DocResult r : success) {
            System.out.printf("[成功] #%d %s | 解析 %d ms + 切块 %d ms | 共 %d 块%n",
                    r.seq(), r.filename(), r.parseCost(), r.chunkCost(), r.chunks().size());
        }
        for (DocResult r : failed) {
            System.out.println("[失败] #" + r.seq() + " " + r.filename() + " | 原因: " + r.error());
        }
    }

    /**
     * 单文档处理结果：成功时携带切块列表与耗时（error 为 null），失败时仅携带错误原因（chunks 为空列表）
     */
    private record DocResult(int seq, String filename, List<DoclingHybridChunker.HybridChunk> chunks,
                             long parseCost, long chunkCost, String error) {

        /**
         * 工厂：构造一个成功结果
         */
        static DocResult success(int seq, String filename, List<DoclingHybridChunker.HybridChunk> chunks,
                                 long parseCost, long chunkCost) {
            return new DocResult(seq, filename, chunks, parseCost, chunkCost, null);
        }

        /**
         * 工厂：构造一个失败结果（切块列表置空，错误原因必填）
         */
        static DocResult failure(int seq, String filename, String error) {
            return new DocResult(seq, filename, List.of(), 0, 0, error);
        }
    }

    /**
     * 提交单份 PDF 的独立流水线「异步解析 -> 切块 -> 逐块预览打印」：
     * 读取文件、提交异步解析任务，解析完成后立即切块并打印预览，任何环节失败都转为失败结果而不抛出
     *
     * @param seq     文档序号（从 1 开始，用于日志与汇总报告对应）
     * @param pdfPath PDF 文件路径
     * @return 该文档的处理结果 future（永不会异常完成，失败信息封装在 DocResult.error 中）
     */
    private CompletableFuture<DocResult> processOne(int seq, Path pdfPath) {
        String filename = pdfPath.getFileName().toString();
        try {
            // 步骤 1：读取本地 PDF 并包装为 Base64 文件源（docling-java 通过 HTTP 上传给 Docling Serve 解析）
            byte[] pdfBytes = Files.readAllBytes(pdfPath);
            FileSource source = FileSource.builder()
                    .filename(filename)
                    .base64String(Base64.getEncoder().encodeToString(pdfBytes))
                    .build();

            // 步骤 2：构建转换请求并提交异步解析（提交即返回，服务端排队 + worker 并行处理）
            ConvertDocumentRequest request = ConvertDocumentRequest.builder()
                    .source(source)
                    .options(ConvertDocumentOptions.builder()
                            .toFormat(OutputFormat.JSON)
                            .build())
                    .target(InBodyTarget.builder().build())
                    .build();
            long start = System.currentTimeMillis();
            return doclingApi.convertSourceAsync(request).toCompletableFuture()

                    // 步骤 3：该文档解析完成 -> 立即切块（文档级独立推进，不等其他文档）
                    .thenApply(response -> {
                        // 解析响应带业务错误则直接抛出，由下方 handle 统一转为失败结果
                        InBodyConvertDocumentResponse body = (InBodyConvertDocumentResponse) response;
                        if (!body.getErrors().isEmpty()) {
                            StringBuilder reasons = new StringBuilder();
                            body.getErrors().forEach(e -> reasons.append(e.getComponentType())
                                    .append(" - ").append(e.getErrorMessage()).append("; "));
                            throw new IllegalStateException("Docling 解析失败: " + reasons);
                        }

                        // 取出结构树并做 HybridChunker 语义切块，分别记录解析/切块耗时
                        DoclingDocument doc = body.getDocument().getJsonContent();
                        long parseCost = System.currentTimeMillis() - start;
                        long chunkStart = System.currentTimeMillis();
                        List<DoclingHybridChunker.HybridChunk> hybridChunks = hybridChunker.chunk(doc);
                        long chunkCost = System.currentTimeMillis() - chunkStart;
                        return DocResult.success(seq, filename, hybridChunks, parseCost, chunkCost);
                    })

                    // 步骤 4：该文档切块完成 -> 打印逐块预览（并发时输出会按各文档完成先后交错）
                    .thenApply(result -> {
                        printChunkPreview(result);
                        return result;
                    })

                    // 步骤 5：兜底：链条上任何异常（解析业务错误/切块异常/HTTP 失败）都转为失败结果，保证不向外抛
                    .handle((result, ex) -> ex == null ? result
                            : DocResult.failure(seq, filename, rootMessage(ex)));
        } catch (IOException e) {
            // 读文件失败：直接以失败结果完成，与其他失败走同一套汇总逻辑
            return CompletableFuture.completedFuture(
                    DocResult.failure(seq, filename, "读取文件失败: " + e.getMessage()));
        }
    }

    /**
     * 打印单个文档的切块预览：总览行 + 逐块「页码区间 | 标题路径 | 80 字符文本预览」，与入库格式保持一致
     *
     * @param result 单文档成功结果
     */
    private void printChunkPreview(DocResult result) {
        System.out.printf("[#%d] === %s | 解析 %d ms + 切块 %d ms | 共 %d 块 ===%n",
                result.seq(), stripExtension(result.filename()),
                result.parseCost(), result.chunkCost(), result.chunks().size());
        for (int i = 0; i < result.chunks().size(); i++) {
            DoclingHybridChunker.HybridChunk c = result.chunks().get(i);

            // 块级元数据格式化：页码区间 + 标题路径
            int first = c.pageNos().isEmpty() ? 0 : c.pageNos().get(0);
            int last = c.pageNos().isEmpty() ? 0 : c.pageNos().get(c.pageNos().size() - 1);
            String page = first == last ? String.valueOf(first) : first + "-" + last;
            String section = c.headings().isEmpty() ? "未知章节" : String.join(" > ", c.headings());

            // 文本预览截断到 80 字符并压平换行，保证一行一块
            String preview = c.text().length() > 80 ? c.text().substring(0, 80) + "..." : c.text();
            System.out.printf("[#%d]   #%d | page=%s | %s | %s%n",
                    result.seq(), i + 1, page, section, preview.replace('\n', ' '));
        }
    }

    /**
     * 展开异常链取最根本的错误信息（剥掉 CompletableFuture 包装的 CompletionException 层）
     *
     * @param ex 链条上抛出的异常
     * @return 最内层异常的「类型名: 消息」
     */
    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /**
     * 把 HybridChunker 产出的切块转为带元数据的 TextSegment：
     * ID 按「jun-gong-序号」生成保持物理阅读顺序可排序，section 取标题栈路径，
     * page 取块覆盖页码区间，并用序号构建 prev_id/next_id 物理邻居链
     *
     * @param hybridChunks HybridChunker 切块结果（物理阅读顺序）
     * @param title        文档大标题（文件名去后缀）
     * @return 带元数据的切块列表，可直接向量化入库
     */
    private List<TextSegment> toTextSegments(List<DoclingHybridChunker.HybridChunk> hybridChunks, String title) {
        // 步骤 1：逐块装配元数据：id/doc_id/title/section/page/block_type/prev_id/next_id
        List<TextSegment> chunks = new ArrayList<>(hybridChunks.size());
        for (int i = 0; i < hybridChunks.size(); i++) {
            DoclingHybridChunker.HybridChunk c = hybridChunks.get(i);

            // 步骤 2：页码列表转为区间格式（如 "3" 或 "3-4"），与原有元数据格式保持一致
            int first = c.pageNos().isEmpty() ? 0 : c.pageNos().get(0);
            int last = c.pageNos().isEmpty() ? 0 : c.pageNos().get(c.pageNos().size() - 1);
            String page = first == last ? String.valueOf(first) : first + "-" + last;

            // 步骤 3：标题栈路径为空时用「未知章节」占位，保证 ES 中该字段始终有值可过滤
            String section = c.headings().isEmpty() ? "未知章节" : String.join(" > ", c.headings());

            // 步骤 4：按物理序号生成 ID 与前后邻居 ID（首块 prev 为空、末块 next 为空）
            Metadata md = new Metadata()
                    .put("id", "jun-gong-" + (i + 1))
                    .put("doc_id", "1")
                    .put("title", title)
                    .put("section", section)
                    .put("page", page)
                    .put("prev_id", i > 0 ? "jun-gong-" + i : "")
                    .put("next_id", i < hybridChunks.size() - 1 ? "jun-gong-" + (i + 2) : "")
                    .put("block_type", c.blockType());
            chunks.add(TextSegment.from(c.text(), md));
        }
        return chunks;
    }

    /**
     * 去掉文件名的扩展名，得到文档标题
     *
     * @param filename 原始文件名（可为 null）
     * @return 去掉最后一个 "." 之后部分的结果；入参为空时返回空串
     */
    private String stripExtension(String filename) {
        // 空值保护 + 截掉最后一个点及之后的部分
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 检索验证：把查询问题向量化后在 ES 中做近邻检索，打印最相关的前几条 Chunk
     */
    @Test
    void search() {
        // 步骤 1：准备查询问题并向量化
        String question = "竣工日期";
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        // 步骤 2：在 ES 向量库中检索最相关的 3 条 Chunk
        EmbeddingSearchResult<TextSegment> results = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(10)
                .build());

        // 步骤 3：逐条打印得分、元数据与内容，并用 prev_id/next_id 反查邻居块原文拼接上下文
        System.out.println("=== 检索命中 " + results.matches().size() + " 条 ===");
        RestClient client = RestClient.builder(HttpHost.create(ES_URL)).build();
        for (EmbeddingMatch<TextSegment> match : results.matches()) {
            Metadata md = match.embedded().metadata();
            System.out.println("score=" + match.score()
                    + " | id=" + md.getString("id")
                    + " | doc_id=" + md.getString("doc_id")
                    + " | title=" + md.getString("title")
                    + " | section=" + md.getString("section")
                    + " | page=" + md.getString("page")
                    + " | block_type=" + md.getString("block_type")
                    + " | prev_id=" + md.getString("prev_id")
                    + " | next_id=" + md.getString("next_id"));
            // 打印本块原文，再按 prev_id/next_id ID 反查 ES 取前后物理邻居块的原文
            System.out.println("    [本块] " + match.embedded().text());
        }
        try {
            client.close();
        } catch (IOException e) {
            // 关闭失败不影响检索结果展示
        }
    }

    /**
     * 清空索引数据：对 eka-rag 索引执行 delete_by_query(match_all)，
     * 删除索引内全部 Chunk 文档但保留索引结构与向量 mapping，便于重新灌入数据
     *
     * @throws IOException ES 请求读写异常
     */
    @Test
    void clearIndex() throws IOException {
        // 步骤 1：创建一次性的 ES 低级客户端（try-with-resources 保证用完即关，不影响 Spring 管理的 Bean）
        try (RestClient client = RestClient.builder(HttpHost.create(ES_URL)).build()) {
            // 步骤 2：构造 delete_by_query 请求，match_all 条件表示删除索引内所有文档
            Request request = new Request("POST", "/" + INDEX + "/_delete_by_query");
            request.setJsonEntity("{\"query\": {\"match_all\": {}}}");

            // 步骤 3：执行删除并打印响应中的 deleted 数量，人工确认清理结果
            Response response = client.performRequest(request);
            System.out.println("=== 清空索引 " + INDEX + " 完成，响应: "
                    + EntityUtils.toString(response.getEntity()) + " ===");
        }
    }

    /**
     * 导出索引内全部块数据：先列出文件概览清单（每个文件一行：file_id/项目/卷/件/年度/块数），
     * 再按文件分组逐块打印完整元数据与原文，便于人工核对切块效果与元数据装配
     *
     * @throws IOException ES 请求发送或响应读取失败时抛出
     */
    @Test
    void dumpAllChunks() throws IOException {
        // 步骤 1：创建一次性的 ES 低级客户端（try-with-resources 保证用完即关，不影响 Spring 管理的 Bean）
        try (RestClient client = RestClient.builder(HttpHost.create(ES_URL)).build()) {
            // 步骤 2：match_all 拉取索引内全量文档（size 取一个足够大的上限）
            Request request = new Request("POST", "/" + INDEX + "/_search");
            request.setJsonEntity("{\"size\": 1000, \"query\": {\"match_all\": {}}}");
            Response response = client.performRequest(request);
            List<JsonNode> hits = new ArrayList<>();
            new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()))
                    .path("hits").path("hits").forEach(hits::add);

            // 步骤 3：按 doc_id 分组（新数据一个文件一组，旧数据共享 doc_id="1" 归为一组）
            Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
            for (JsonNode hit : hits) {
                String docId = hit.path("_source").path("metadata").path("doc_id").asText("");
                groups.computeIfAbsent(docId, k -> new ArrayList<>()).add(hit);
            }

            // 步骤 4：打印文件概览清单：每个文件一行，元数据取组内第一块（同文件各块元数据一致）
            System.out.println("=== 索引 " + INDEX + " 共 " + hits.size() + " 块 / " + groups.size() + " 个文件 ===");
            System.out.println("---------- 文件清单 ----------");
            int fileNo = 1;
            for (Map.Entry<String, List<JsonNode>> entry : groups.entrySet()) {
                JsonNode md = entry.getValue().get(0).path("_source").path("metadata");
                System.out.printf("#%d | doc_id=%s | file_id=%s | form_year=%s | 项目=%s | 卷=%s | 件=%s | %d 块%n",
                        fileNo++, entry.getKey(),
                        md.path("file_id").asText("-"), md.path("form_year").asText("-"),
                        md.path("project_name").asText("-"), md.path("volume_name").asText("-"),
                        md.path("item_name").asText("-"), entry.getValue().size());
            }

            // 步骤 5：逐文件展示全部块：文件头分隔线 + 逐块完整元数据（含身份五字段与块结构字段）与原文
            fileNo = 1;
            for (Map.Entry<String, List<JsonNode>> entry : groups.entrySet()) {
                List<JsonNode> fileHits = entry.getValue();

                // 组内按块 id 末尾的数字序号升序排序，使输出顺序与入库时的物理阅读顺序一致
                fileHits.sort(Comparator.comparingInt(h -> {
                    String id = h.path("_source").path("metadata").path("id").asText();
                    String num = id.replaceAll("\\D", "");
                    return num.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(num);
                }));

                System.out.println();
                System.out.println("============================================================");
                System.out.printf("【文件 #%d】doc_id=%s，共 %d 块%n", fileNo++, entry.getKey(), fileHits.size());
                System.out.println("============================================================");
                for (JsonNode hit : fileHits) {
                    JsonNode src = hit.path("_source");
                    JsonNode md = src.path("metadata");

                    // 块结构元数据：唯一 ID 与前后邻居链、块类型、页码区间
                    System.out.println("------------------------------------------------------------");
                    System.out.println("id=" + md.path("id").asText()
                            + " | prev_id=" + md.path("prev_id").asText()
                            + " | next_id=" + md.path("next_id").asText()
                            + " | block_type=" + md.path("block_type").asText()
                            + " | page=" + md.path("page").asText());

                    // 身份元数据：档案定位五字段（老数据无此组，显示为空）
                    System.out.println("file_id=" + md.path("file_id").asText()
                            + " | form_year=" + md.path("form_year").asText()
                            + " | project_name=" + md.path("project_name").asText()
                            + " | volume_name=" + md.path("volume_name").asText()
                            + " | item_name=" + md.path("item_name").asText());

                    // 入库管理元数据：向量模型出处与插入/修改时间（老数据无此组，显示为空）
                    System.out.println("embedding_model=" + md.path("embedding_model").asText()
                            + " | created_at=" + md.path("created_at").asText()
                            + " | updated_at=" + md.path("updated_at").asText());

                    // 章节元数据：标题路径（新）与文档大标题（旧），随后是本块完整原文
                    System.out.println("section=" + md.path("section").asText()
                            + " | title=" + md.path("title").asText());
                    System.out.println(src.path("text").asText());
                }
            }
            System.out.println();
            System.out.println("=== 导出完成 ===");
        }
    }
}
