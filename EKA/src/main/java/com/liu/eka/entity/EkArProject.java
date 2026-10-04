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
 * 工程项目表实体（ek_ar_project）：档案归属的最顶层业务单元，
 * 为 RAG 切块元数据提供 project_name（档案身份三名字之一）
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ek_ar_project")
public class EkArProject {

    /** 主键：业务可读 ID（如 prj-cbcz），非自增 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属分类 ID（对应 ek_category） */
    private String categoryId;

    /** 工程项目编号 */
    private String projectCode;

    /** 工程项目名称 */
    private String projectName;

    /** 项目描述 */
    private String description;

    /** 逻辑删除标记：0 正常 / 1 已删除 */
    private String delFlag;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
