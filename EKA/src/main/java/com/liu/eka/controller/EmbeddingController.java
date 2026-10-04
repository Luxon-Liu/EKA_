package com.liu.eka.controller;

import com.liu.eka.entity.rag.BatchIngestResponse;
import com.liu.eka.entity.rag.FileIngestResult;
import com.liu.eka.service.RagIngestService;
import com.liu.eka.common.Result;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 知识库入口接口：对外提供文件批量入库能力，
 * 接收文件 ID 集合，经四表联查装配档案元数据后走「异步解析 -> 切块 -> 向量化 -> 入 ES」流水线；
 * 服务层回传逐文件明细（块数/耗时/错误），由本控制器解析输出精细日志，前端响应体只含成功/失败计数
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
@Validated
public class EmbeddingController {

    /** RAG 批量入库服务 */
    private final RagIngestService ragIngestService;

    /**
     * 批量入库：按文件 ID 集合将已有档案的原文文件导入向量知识库；
     * 入参由 @NotEmpty 注解校验（空集合直接 400），业务异常由全局异常翻译器转 Result
     *
     * @param fileIds 原文文件 ID 集合（对应 ek_ar_file.id），JSON 数组形式，如 ["file-cbcz-02-1"]
     * @return 统一返回体，载荷为批量入库汇总（前端仅序列化成功数/失败数两个计数，逐文件明细被 @JsonIgnore 拦截）
     */
    @PostMapping("/ingest")
    public Result<BatchIngestResponse> ingest(
            @NotEmpty(message = "文件 ID 集合不允许为空") @RequestBody List<String> fileIds) {
        // 步骤 1：记录请求参数，作为入库操作的入口留痕（非空由注解保证）
        log.info("收到批量入库请求：{} 个文件 {}", fileIds.size(), fileIds);

        // 步骤 2：委托服务层执行文件级独立流水线，统计接口整批耗时（仅日志输出，不返回前端）
        long start = System.currentTimeMillis();
        BatchIngestResponse response = ragIngestService.ingestAll(fileIds);

        // 步骤 3：解析逐文件明细，精细输出每个文档的入库结果（成功含块数与解析/切块耗时，失败含错误原因）
        for (FileIngestResult result : response.getResults()) {
            if (result.getError() == null) {
                log.info("文件入库成功：{} - {} - {} 块（解析 {} ms + 切块 {} ms）",
                        result.getFileId(), result.getFilename(), result.getChunkCount(),
                        result.getParseCost(), result.getChunkCost());
            } else {
                log.warn("文件入库失败：{} - {} - {}",
                        result.getFileId(), result.getFilename(), result.getError());
            }
        }

        // 步骤 4：输出整批汇总日志并返回统一返回体（响应体经 @JsonIgnore 只剩成功/失败计数）
        log.info("批量入库接口结束：成功 {} / 失败 {}，耗时 {} ms",
                response.getSuccessCount(), response.getFailedCount(),
                System.currentTimeMillis() - start);
        return Result.ok(response);
    }
}
