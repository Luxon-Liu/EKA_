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
 * 案卷主表实体（ek_ar_main）：既承载「单件归档」的档案本身，也是「立卷归档」中卷的载体，
 * 为 RAG 切块元数据提供卷名（卷内文件的 volume_name）与 form_year（归档年度）
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ek_ar_main")
public class EkArMain {

    /** 主键：业务可读 ID（如 main-cbcz-02），非自增 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属分类 ID（对应 ek_category） */
    private String categoryId;

    /** 归属工程项目 ID（对应 ek_ar_project.id） */
    private String projectId;

    /** 档号：DA/T 13 编码规则，archive_code + del_flag 联合唯一 */
    private String archiveCode;

    /** 题名：单件时即件名，立卷时为卷名 */
    private String title;

    /** 档案状态：0 草稿 1 待审核 2 移交中 3 已入库 4 已驳回 5 已销毁 6 已到期 7 已锁定 */
    private String status;

    /** 保管期限 */
    private String retentionPeriod;

    /** 密级：0 公开 1 内部 2 秘密 3 机密 4 绝密 */
    private String securityLevel;

    /** 归档年度：档案第一查找维度，进入 RAG 元数据参与检索与范围过滤 */
    private Integer formYear;

    /** 总页数 */
    private Integer pageCount;

    /** 逻辑删除标记：0 正常 / 1 已删除 */
    private String delFlag;

    /** 创建人 */
    private String createdBy;

    /** 更新人 */
    private String updatedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
