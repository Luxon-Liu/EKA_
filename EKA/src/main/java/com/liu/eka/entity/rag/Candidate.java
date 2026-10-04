package com.liu.eka.entity.rag;

import dev.langchain4j.data.document.Metadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 混合检索候选项：统一承载向量检索与 ES 精准检索两路命中的同一份文档块，
 * id 为元数据里的业务唯一键（fileId-序号，如 file-cbcz-02-1-3），origin 标记该候选来自哪一路召回，
 * score 记录当前环节的相关度分数（召回阶段为向量/BM25 分数，重排后被 relevance_score 覆盖）
 *
 * @author Luxon
 * @date 2026/09/02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Candidate {

    /** 候选块的业务唯一 id（元数据 id 字段，fileId-序号格式） */
    private String id;

    /** 候选块原文 */
    private String text;

    /** 候选块元数据（含 project_name/volume_name/item_name/form_year/file_id 等完整字段） */
    private Metadata metadata;

    /** 召回来源标识：向量检索 / 精准检索 */
    private String origin;

    /** 相关度分数：召回阶段为向量相似度或 BM25 分数，重排序后为 relevance_score */
    private double score;
}
