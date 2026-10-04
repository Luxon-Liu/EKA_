package com.liu.eka.entity.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个文件的档案身份元数据：由 file_id 经四表联查（file -> side -> main -> project）装配而成，
 * 五个检索字段（projectName/volumeName/itemName/formYear/fileId）随切块写入向量库，
 * fileName/filePath 仅用于读取原始 PDF，不入向量库
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileMetadata {

    /** 归属工程项目名 */
    private String projectName;

    /** 卷名：单件归档时为空串，立卷归档时为所属卷的题名 */
    private String volumeName;

    /** 件名：单件取主表题名，卷内取卷内题名（不取文件名） */
    private String itemName;

    /** 归档年度（取自主表，归档年度不可变、可范围过滤） */
    private Integer formYear;

    /** 原文文件 ID：向量库中文档的唯一身份，也是反查 MySQL 的万能钥匙 */
    private String fileId;

    /** 原始文件名（含扩展名），上传给 Docling 时使用 */
    private String fileName;

    /** 文件存储路径：相对 file.archive-root 的相对路径 */
    private String filePath;
}
