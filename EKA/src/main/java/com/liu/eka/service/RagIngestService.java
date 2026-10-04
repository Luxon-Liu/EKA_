package com.liu.eka.service;

import com.liu.eka.entity.rag.BatchIngestResponse;

import java.util.List;

/**
 * RAG 批量入库服务接口：定义文件批量导入向量知识库的业务契约，
 * 具体的「查元数据 -> 异步解析 -> 切块 -> 向量化 -> 写入 ES」流水线由实现类完成
 *
 * @author Luxon
 * @date 2026/09/02
 */
public interface RagIngestService {

    /**
     * 批量入库：对每个 file_id 独立提交处理流水线（解析 -> 切块 -> 向量化 -> 入 ES），
     * 等全部流水线结束后汇总成败（文件级失败语义：部分成功有独立价值，不做 all-or-nothing）
     *
     * @param fileIds 原文文件 ID 集合（对应 ek_ar_file.id）
     * @return 批量入库汇总报告（总数/成功数/失败数/逐文件明细）
     */
    BatchIngestResponse ingestAll(List<String> fileIds);
}
