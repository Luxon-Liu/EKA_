package com.liu.eka.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 原文文件表实体（ek_ar_file）：一档多文件设计中的原文文件条目，
 * 是 RAG 入库的入口实体——file_id 即向量库中的文档身份，file_path 定位原始 PDF
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ek_ar_file")
public class EkArFile {

    /** 主键：业务可读 ID（如 file-cbcz-02-1），同时作为向量库中该文件的唯一身份标识 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 归档条目 ID：指向 ek_ar_main.id（单件）或 ek_ar_side.id（卷内件） */
    private String archiveId;

    /** 原始文件名（含扩展名） */
    private String fileName;

    /** 文件存储路径：相对 file.archive-root 根目录的相对路径 */
    private String filePath;

    /** 文件类型：0 正文 1 附件 2 原文扫描件 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件 MD5 摘要，用于完整性校验与去重 */
    private String md5;

    /** 逻辑删除标记：0 正常 / 1 已删除 */
    private String delFlag;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
