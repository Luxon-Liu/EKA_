package com.liu.eka.entity.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 档案身份过滤条件（RagSearchTool 的 @Tool 入参模型）：
 * 按归属工程项目、卷、件、归档年度对检索结果做精确等值过滤，四个字段全部可空，
 * 留空(null/空白串)的字段不参与过滤；对象整体为空时等同于不过滤。
 * 该条件会被下推到两路召回的查询阶段（向量路转 langchain4j Filter，精准检索路转 ES term），
 * 字段与向量库 ES 元数据键一一对应（camelCase -> snake_case：project_name/volume_name/item_name/form_year）。
 *
 * @author Luxon
 * @date 2026/09/03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveFilter {

    /** 归属工程项目名（精确匹配，对应元数据键 project_name），留空不过滤 */
    private String projectName;

    /** 卷名（精确匹配，对应元数据键 volume_name），留空不过滤 */
    private String volumeName;

    /** 件名（精确匹配，对应元数据键 item_name），留空不过滤 */
    private String itemName;

    /** 归档年度（精确匹配，对应元数据键 form_year，按年份数字比较），留空不过滤 */
    private String formYear;

    /**
     * 判断该过滤条件是否为空：四个字段全未填写时为空，此时不对检索结果做任何裁剪。
     * 该方法是纯逻辑判断而非数据字段，标注 @JsonIgnore 防止被当作 bean 属性序列化进
     * 工具参数 JSON（否则模型/序列化端会把 isEmpty 与数据字段一并回传，反序列化报错）
     *
     * @return true 表示无条件（无任何字段参与过滤）
     */
    @JsonIgnore
    public boolean isEmpty() {
        // 只要有一个字段填了值就不算空过滤条件
        return !isFilled(projectName) && !isFilled(volumeName)
                && !isFilled(itemName) && !isFilled(formYear);
    }

    /**
     * 解析归档年度为数字：ES 侧 form_year 为 long 字段，下推 term 必须用数值比较，
     * 字符串无法命中数值字段；此处统一把入参解析成 Long 供两路召回共用
     *
     * @return form_year 对应的数字年份；未填写时返回 null
     * @throws IllegalArgumentException 入参非数字字符串（如带"年"字、中文）时抛出，便于 Agent 纠正入参
     */
    public Long formYearNumber() {
        // 未填写年度不参与解析，直接返回 null 由调用方跳过该维度
        if (!isFilled(formYear)) {
            return null;
        }
        // 去掉首尾空白后解析为数字年份，解析失败给出明确中文提示
        try {
            return Long.parseLong(formYear.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("归档年度 formYear 必须是数字年份（如 2023），实际收到：" + formYear);
        }
    }

    /**
     * 判断入参字符串是否被填写（非 null 且非空白串）
     *
     * @param value 待判断字符串，可为 null
     * @return true 表示已填写、需要参与过滤
     */
    public static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }
}
