package com.liu.eka.service.impl;

import ai.docling.core.DoclingDocument;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import com.liu.eka.entity.EkArFile;
import com.liu.eka.entity.EkArMain;
import com.liu.eka.entity.EkArProject;
import com.liu.eka.entity.EkArSide;
import com.liu.eka.entity.rag.BatchIngestResponse;
import com.liu.eka.entity.rag.FileIngestResult;
import com.liu.eka.entity.rag.FileMetadata;
import com.liu.eka.mapper.EkArFileMapper;
import com.liu.eka.mapper.EkArMainMapper;
import com.liu.eka.mapper.EkArProjectMapper;
import com.liu.eka.mapper.EkArSideMapper;
import com.liu.eka.service.RagIngestService;
import com.liu.eka.rag.DoclingHybridChunker;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 批量入库服务实现：按文件维度组织「查元数据 -> 异步解析 -> 语义切块 -> 元数据装配 -> 向量化 -> 写入 ES」的独立流水线，
 * 单个文件失败只记入失败清单、不影响其他文件，全部结束后汇总成败；
 * 逐文件的块数/耗时/错误明细封装在返回值中交由控制层输出日志，服务层自身不做日志输出
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Service
@RequiredArgsConstructor
public class RagIngestServiceImpl implements RagIngestService {

    /** 入库时间戳格式：ES 中 created_at/updated_at 字段的统一格式 */
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 原文文件表数据访问 */
    private final EkArFileMapper fileMapper;

    /** 卷内文件表数据访问 */
    private final EkArSideMapper sideMapper;

    /** 案卷主表数据访问 */
    private final EkArMainMapper mainMapper;

    /** 工程项目表数据访问 */
    private final EkArProjectMapper projectMapper;

    /** Docling Serve 客户端（异步解析 PDF） */
    private final DoclingServeApi doclingApi;

    /** HybridChunker 切块器（含切块元数据装配与异常剥壳等 RAG 辅助职责） */
    private final DoclingHybridChunker hybridChunker;

    /** 向量模型（DashScope text-embedding-v3） */
    private final EmbeddingModel embeddingModel;

    /** ES 向量存储 */
    private final ElasticsearchEmbeddingStore store;

    /** 档案原文文件存储根目录：ek_ar_file.file_path 存的是相对此目录的路径 */
    @Value("${file.archive-root}")
    private String fileRoot;

    /** 向量模型提供商标识（取 rag.embedding.provider 配置，如 dashscope），用于组装带厂商前缀的模型标识 */
    @Value("${rag.embedding.provider}")
    private String embeddingProvider;

    /** 向量模型名称（取 rag.embedding.model 配置），随块写入 ES 便于溯源该块的向量出处 */
    @Value("${rag.embedding.model}")
    private String embeddingModelName;

    /**
     * 批量入库：对每个 file_id 独立提交处理流水线（解析 -> 切块 -> 向量化 -> 入 ES），
     * 等全部流水线结束后汇总成败（文件级失败语义：部分成功有独立价值，不做 all-or-nothing）；
     * 逐文件的块数/耗时/错误明细封装在返回值中，由控制层解析输出日志，前端响应只见成功/失败计数
     *
     * @param fileIds 原文文件 ID 集合（对应 ek_ar_file.id），调用方保证非空
     * @return 批量入库汇总（成功数/失败数/逐文件明细，明细经 @JsonIgnore 不进响应体）
     */
    @Override
    public BatchIngestResponse ingestAll(List<String> fileIds) {
        // 步骤 1：逐个文件装配元数据并提交独立流水线；元数据查不到的文件直接以失败结果完成，不进入流水线
        List<CompletableFuture<FileIngestResult>> futures = new ArrayList<>(fileIds.size());
        for (String fileId : fileIds) {
            FileMetadata meta = loadMetadata(fileId);
            if (meta == null) {
                futures.add(CompletableFuture.completedFuture(
                        FileIngestResult.failure(fileId, "", "文件不存在或档案元数据缺失")));
            } else {
                futures.add(processOne(meta));
            }
        }

        // 步骤 2：等待全部流水线结束（失败已在内部转为失败结果，此处不会抛出业务异常）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 步骤 3：汇总成败：按是否携带错误信息统计成功/失败数量，明细按提交顺序收集
        List<FileIngestResult> results = new ArrayList<>(futures.size());
        int successCount = 0;
        int failedCount = 0;
        for (CompletableFuture<FileIngestResult> future : futures) {
            FileIngestResult result = future.join();
            if (result.getError() == null) {
                successCount++;
            } else {
                failedCount++;
            }
            results.add(result);
        }

        // 步骤 4：返回汇总结果（计数 + 明细），日志职责由控制层承担
        return new BatchIngestResponse(successCount, failedCount, results);
    }

    /**
     * 按 file_id 四表联查装配档案身份元数据：
     * file -> 先按卷内件（ek_ar_side）找归属，找不到再按单件（ek_ar_main）找归属，最后取工程项目名
     *
     * @param fileId 原文文件 ID
     * @return 装配好的元数据；文件不存在或档案链断裂时返回 null
     */
    private FileMetadata loadMetadata(String fileId) {
        // 步骤 1：查原文文件条目，入口记录不存在直接返回 null（由调用方转为失败结果）
        EkArFile file = fileMapper.selectById(fileId);
        if (file == null) {
            return null;
        }

        // 步骤 2：判断归档形态——archive_id 先匹配卷内件（ek_ar_side），匹配上说明是立卷归档
        EkArSide side = sideMapper.selectById(file.getArchiveId());
        EkArMain main;
        String volumeName;
        String itemName;
        if (side != null) {
            // 卷内件：卷名取所属卷的题名，件名取卷内题名（同件多文件共享件名，故不取文件名）
            main = mainMapper.selectById(side.getMainId());
            volumeName = main != null ? main.getTitle() : "";
            itemName = side.getTitle();
        } else {
            // 单件：archive_id 直指主表，卷名为空串，件名取主表题名
            main = mainMapper.selectById(file.getArchiveId());
            volumeName = "";
            itemName = main != null ? main.getTitle() : "";
        }

        // 步骤 3：档案链断裂（主表缺失）无法定位归属，返回 null 转失败结果
        if (main == null) {
            return null;
        }

        // 步骤 4：取工程项目名（档案身份的最顶层维度）
        EkArProject project = projectMapper.selectById(main.getProjectId());
        String projectName = project != null ? project.getProjectName() : "";

        // 步骤 5：装配元数据对象（form_year 取自主表，归档年度不可变、可范围过滤）
        return new FileMetadata(projectName, volumeName, itemName,
                main.getFormYear(), fileId, file.getFileName(), file.getFilePath());
    }

    /**
     * 提交单个文件的入库流水线「异步解析 -> 切块 -> 元数据装配 -> 向量化 -> 写入 ES」：
     * 解析完成后立即在回调中切块并入库（文档级独立推进，不等其他文件），任何环节失败都转为失败结果而不抛出
     *
     * @param meta 档案身份元数据（含文件定位路径）
     * @return 该文件的处理结果 future（永不会异常完成，失败信息封装在 FileIngestResult.error 中）
     */
    private CompletableFuture<FileIngestResult> processOne(FileMetadata meta) {
        try {
            // 步骤 1：按存储根目录拼出绝对路径，读取原始 PDF 并包装为 Base64 文件源
            Path path = Path.of(fileRoot, meta.getFilePath());
            byte[] pdfBytes = Files.readAllBytes(path);
            FileSource source = FileSource.builder()
                    .filename(meta.getFileName())
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

                    // 步骤 3：该文件解析完成 -> 检查业务错误 -> 切块 -> 装配元数据 -> 向量化 -> 写入 ES；
                    // thenApplyAsync 让重 IO 的向量化离开 Docling 客户端的回调线程（默认公共线程池）
                    .thenApplyAsync(response -> {
                        // HTTP 200 不代表解析成功：响应体带业务错误时抛出，由下方 handle 统一转为失败结果
                        InBodyConvertDocumentResponse body = (InBodyConvertDocumentResponse) response;
                        if (!body.getErrors().isEmpty()) {
                            StringBuilder reasons = new StringBuilder();
                            body.getErrors().forEach(e -> reasons.append(e.getComponentType())
                                    .append(" - ").append(e.getErrorMessage()).append("; "));
                            throw new IllegalStateException("Docling 解析失败: " + reasons);
                        }

                        // 取出结构树并做 HybridChunker 语义切块，装配入库元数据，分别记录解析/切块耗时
                        DoclingDocument doc = body.getDocument().getJsonContent();
                        long parseCost = System.currentTimeMillis() - start;
                        long chunkStart = System.currentTimeMillis();
                        List<TextSegment> chunks = toTextSegments(
                                hybridChunker.chunk(doc), meta);
                        long chunkCost = System.currentTimeMillis() - chunkStart;

                        // 批量向量化所有切块（DashScope 单批 10 条已在模型 Bean 内处理）
                        List<Embedding> embeddings = embeddingModel.embedAll(chunks).content();

                        // 每块用「fileId-序号」作为唯一 ID 写入 ES（同文件重复入库会叠加，重灌前需先清理该文件的块）
                        List<String> ids = chunks.stream()
                                .map(c -> c.metadata().getString("id")).toList();
                        store.addAll(ids, embeddings, chunks);
                        return FileIngestResult.success(meta.getFileId(), meta.getFileName(),
                                chunks.size(), parseCost, chunkCost);
                    })

                    // 步骤 4：兜底：链条上任何异常（解析业务错误/切块异常/向量化/HTTP 失败）都转为失败结果，保证不向外抛
                    .handle((result, ex) -> ex == null ? result
                            : FileIngestResult.failure(meta.getFileId(), meta.getFileName(),
                            rootMessage(ex)));
        } catch (IOException e) {
            // 读文件失败：直接以失败结果完成，与其他失败走同一套汇总逻辑（错误信息由控制层输出日志）
            return CompletableFuture.completedFuture(
                    FileIngestResult.failure(meta.getFileId(), meta.getFileName(),
                            "读取文件失败: " + e.getMessage()));
        }
    }

    /**
     * 把切块器产出的切块装配为可直接向量化入库的 TextSegment 列表：
     * ID 按「fileId-物理序号」生成保证同一文件内可排序，并用序号构建 prev_id/next_id 物理邻居链；
     * 元数据分三组——档案身份五字段（project_name/volume_name/item_name/form_year/file_id）、
     * 块结构字段（id/doc_id/section/page/prev_id/next_id/block_type）
     * 与入库管理字段（embedding_model/created_at/updated_at，同一文件整批共用同一时间戳）
     *
     * @param hybridChunks 切块器产出的切块结果（物理阅读顺序）
     * @param meta         档案身份元数据（四表联查装配结果）
     * @return 带元数据的切块列表
     */
    private List<TextSegment> toTextSegments(List<DoclingHybridChunker.HybridChunk> hybridChunks,
                                             FileMetadata meta) {
        // 步骤 1：取本次入库时刻并格式化，插入时间与修改时间初始一致（将来更新块内容时只刷 updated_at）
        String now = LocalDateTime.now().format(TS_FORMAT);

        // 步骤 2：逐块装配元数据，块 ID 前缀统一用 file_id，天然按文件隔离且可排序
        List<TextSegment> chunks = new ArrayList<>(hybridChunks.size());
        for (int i = 0; i < hybridChunks.size(); i++) {
            DoclingHybridChunker.HybridChunk c = hybridChunks.get(i);

            // 步骤 3：页码列表转为区间格式（如 "3" 或 "3-4"），便于检索结果展示与过滤
            int first = c.pageNos().isEmpty() ? 0 : c.pageNos().get(0);
            int last = c.pageNos().isEmpty() ? 0 : c.pageNos().get(c.pageNos().size() - 1);
            String page = first == last ? String.valueOf(first) : first + "-" + last;

            // 步骤 4：标题栈路径压平为单值，为空时用「未知章节」占位，保证 ES 中该字段始终有值可过滤
            String section = c.headings().isEmpty() ? "未知章节" : String.join(" > ", c.headings());

            // 步骤 5：装配档案身份五字段、块结构字段与入库管理字段（prev/next 邻居链按物理序号生成）
            Metadata md = new Metadata()
                    .put("id", meta.getFileId() + "-" + (i + 1))
                    .put("doc_id", meta.getFileId())
                    .put("project_name", meta.getProjectName())
                    .put("volume_name", meta.getVolumeName())
                    .put("item_name", meta.getItemName())
                    .put("form_year", meta.getFormYear())
                    .put("file_id", meta.getFileId())
                    .put("section", section)
                    .put("page", page)
                    .put("prev_id", i > 0 ? meta.getFileId() + "-" + i : "")
                    .put("next_id", i < hybridChunks.size() - 1 ? meta.getFileId() + "-" + (i + 2) : "")
                    .put("block_type", c.blockType())
                    .put("embedding_model", embeddingProvider + ":" + embeddingModelName)
                    .put("created_at", now)
                    .put("updated_at", now);
            chunks.add(TextSegment.from(c.text(), md));
        }
        return chunks;
    }

    /**
     * 展开异常链取最根本的错误信息（剥掉 CompletableFuture 包装的 CompletionException 层），
     * 供异步流水线的 handle 兜底环节提取失败原因
     *
     * @param ex 链条上抛出的异常
     * @return 最内层异常的「类型名: 消息」
     */
    private String rootMessage(Throwable ex) {
        // 步骤 1：沿 getCause 逐层下钻，直到最内层（自引用视为终止，防御异常链成环）
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
