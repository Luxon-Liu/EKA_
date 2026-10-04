package com.liu.eka.entity.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个文件的入库处理结果：成功时携带入库块数与各环节耗时（error 为 null），
 * 失败时仅携带错误原因（chunkCount 为 0）——单个文件的失败不影响其他文件。
 * 本对象由服务层返回给控制层做逐文件精细日志，经 @JsonIgnore 拦截不进响应体
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileIngestResult {

    /** 原文文件 ID */
    private String fileId;

    /** 原始文件名，用于日志展示 */
    private String filename;

    /** 入库的切块数量 */
    private int chunkCount;

    /** 解析耗时（毫秒，含服务端排队与模型加载） */
    private long parseCost;

    /** 切块耗时（毫秒） */
    private long chunkCost;

    /** 失败原因；成功时为 null */
    private String error;

    /**
     * 工厂：构造一个成功结果
     *
     * @param fileId     原文文件 ID
     * @param filename   原始文件名
     * @param chunkCount 入库块数
     * @param parseCost  解析耗时（毫秒）
     * @param chunkCost  切块耗时（毫秒）
     * @return 成功结果（error 为 null）
     */
    public static FileIngestResult success(String fileId, String filename, int chunkCount,
                                           long parseCost, long chunkCost) {
        return new FileIngestResult(fileId, filename, chunkCount, parseCost, chunkCost, null);
    }

    /**
     * 工厂：构造一个失败结果（块数与耗时归零，错误原因必填）
     *
     * @param fileId   原文文件 ID
     * @param filename 原始文件名
     * @param error    失败原因
     * @return 失败结果
     */
    public static FileIngestResult failure(String fileId, String filename, String error) {
        return new FileIngestResult(fileId, filename, 0, 0, 0, error);
    }
}
