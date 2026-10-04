package com.liu.eka.entity.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量入库响应体：文件级独立流水线的汇总结果（失败语义：部分成功有独立价值，不做 all-or-nothing）。
 * 前端响应体只序列化成功/失败两个计数；逐文件的块数、耗时、错误明细（results）
 * 被 @JsonIgnore 拦截不进响应体，仅供控制层解析后输出精细日志
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchIngestResponse {

    /** 成功入库的文件数 */
    private int successCount;

    /** 失败的文件数 */
    private int failedCount;

    /** 逐文件的成败明细（@JsonIgnore：仅服务端内部流转，不返回前端） */
    @JsonIgnore
    private List<FileIngestResult> results;
}
