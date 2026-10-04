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
 * 卷内文件表实体（ek_ar_side）：立卷归档时卷内的单件条目，
 * 为 RAG 切块元数据提供件名（卷内文件的 item_name），并经 main_id 关联到所属卷
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ek_ar_side")
public class EkArSide {

    /** 主键：业务可读 ID（如 side-cbcz-02-1），非自增 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属卷 ID（对应 ek_ar_main.id） */
    private String mainId;

    /** 卷内题名：即该件的正式件名 */
    private String title;

    /** 卷内排序号 */
    private Integer sequenceNo;

    /** 起始页码 */
    private Integer pageNo;

    /** 本件页数 */
    private Integer pageCount;

    /** 逻辑删除标记：0 正常 / 1 已删除 */
    private String delFlag;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
