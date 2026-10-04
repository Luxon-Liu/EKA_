package com.liu.eka.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liu.eka.entity.rag.ArchiveFilter;
import com.liu.eka.tool.RagSearchTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagSearchTool 检索工具演示测试：不再为工具方法另写便捷包装或同名重载，
 * 而是通过 langchain4j 的 DefaultToolExecutor 直接执行 @Tool 方法——
 * 与 AiServices 编排内部同一条执行路径（JSON 参数按方法形参名绑定、POJO 反序列化、
 * 业务异常默认转错误文案回传），验证「向量 + 精准双路召回 -> 去重 -> 重排 ->
 * 面向 LLM 文本」的完整链路与档案过滤下推行为。
 *
 * @author Luxon
 * @date 2026/09/03
 */
@SpringBootTest
class RagSearchToolTest {

    /** 被测对象：RAG 混合检索工具（正常路径由 Agent 框架经 AiServices 编排调用，此处经 ToolExecutor 直执行） */
    @Autowired
    private RagSearchTool ragSearchTool;

    /** JSON 序列化器：把工具入参拼装为 ToolExecutionRequest.arguments 的 JSON 文本 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 演示用例 1（混合检索）：竣工验收报告的验收结论是什么
     * 预期命中：老演示数据 jun-gong-13「同意本工程通过竣工验收，工程质量等级评定为合格……」
     */
    @Test
    void searchAcceptanceConclusion() throws Exception {
        // 步骤 1：双路 query 齐全，向量路召回验收结论语义相关块，关键词路锁定「验收结论」字样
        searchAndAssert("竣工验收报告的验收结论是什么",
                "竣工验收 验收结论 质量合格 备案",
                "验收结论",
                null);
    }

    /**
     * 演示用例 2（混合检索）：车陂安置区桩基检测的结论与桩身完整性如何
     * 预期命中：file-cbcz-02-2 桩基工程质量检测报告（含 table 块的桩身完整性分类汇总表）
     */
    @Test
    void searchPileDetection() throws Exception {
        // 步骤 1：向量路命中检测结论语义，关键词路锁定「桩身完整性」专名
        searchAndAssert("车陂安置区一期桩基工程的检测结论是什么，桩身完整性如何",
                "桩基工程质量检测 单桩竖向承载力 桩身完整性",
                "桩身完整性",
                null);
    }

    /**
     * 演示用例 3（仅向量路）：复建安置区一期主体结构验收组的验收结论是什么
     * 预期命中：file-cbcz-02-3-10「验收组……同意通过竣工验收」
     */
    @Test
    void searchMainStructureAcceptanceVectorOnly() throws Exception {
        // 步骤 1：esQuery 省略跳过关键词路，仅靠向量语义召回主体结构验收块
        searchAndAssert("复建安置区一期主体结构验收组的验收结论是什么",
                "主体结构验收记录 工程实体质量 资料核查 验收结论",
                null,
                null);
    }

    /**
     * 演示用例 4（仅关键词路）：复建安置区一期竣工验收各专业工程验收意见分别是什么
     * 预期命中：file-cbcz-03-1-14「七、各专业工程质量验收意见」土建/给排水/电气/消防/通风/电梯均同意通过
     */
    @Test
    void searchProfessionalOpinionKeywordOnly() throws Exception {
        // 步骤 1：vectorQuery 省略跳过向量路，靠 BM25 精准命中「验收意见」所在的专业意见章节
        searchAndAssert("复建安置区一期竣工验收各专业工程的质量验收意见是什么",
                null,
                "各专业工程质量验收意见",
                null);
    }

    /**
     * 演示用例 6（档案过滤下推）：限定「车陂村城中村改造项目」且归档年度 2026 检索验收结论。
     * filter 应被下推到两路召回的查询阶段（向量路 kNN 预过滤 + 精准检索路 bool filter），
     * 而非召回后内存过滤：故黄埔老演示数据（无档案身份字段）与车陂 2023 年内容都应被查询阶段直接排除。
     */
    @Test
    void searchFilteredByProjectAndYear() throws Exception {
        // 步骤 1：混合检索并叠加项目+年度过滤（经 ToolExecutor 的 JSON 入参，覆盖 POJO filter 反序列化）
        String result = executeAsAgent("竣工验收的验收结论是什么",
                "竣工验收 验收结论 质量合格",
                "验收结论",
                ArchiveFilter.builder().projectName("车陂村城中村改造项目").formYear("2026").build());

        // 步骤 2：打印返回文本便于人工核对，并断言只含车陂 2026 年命中
        System.out.println("========== 原始问题: 车陂项目2026年验收结论(带filter) ==========");
        System.out.println(result);
        System.out.println();
        assertFalse(result.contains("未检索到相关内容"), "过滤后仍应有车陂 2026 年命中");
        assertFalse(result.contains("jun-gong"), "黄埔老演示数据（无档案身份字段）应被查询阶段排除");
        assertFalse(result.contains("年度: 2023"), "车陂 2023 年内容应被查询阶段排除");
    }

    /**
     * 演示用例 5（参数异常）：两路 query 全空。经 DefaultToolExecutor 执行时业务异常默认不向外抛，
     * 而是被包装为工具结果文案返回——与 AiServices 编排下「错误文案回传 LLM 供其自纠」的行为一致
     */
    @Test
    void searchBothQueriesBlankReturnsErrorText() throws Exception {
        // 步骤 1：只传 required 的 originalQuery，两个召回 query 参数省略，触发工具内部参数校验
        String result = executeAsAgent("随便问点什么", null, null, null);

        // 步骤 2：打印错误文案，并断言其作为工具结果被返回而非抛出异常
        System.out.println("========== 两路query全空: 工具返回的错误文案 ==========");
        System.out.println(result);
        System.out.println();
        assertTrue(result.contains("至少需要提供一个"),
                "应返回参数校验错误文案而非抛出异常，实际返回：\n" + result);
    }

    /**
     * 以 Agent 工具调用语义执行 @Tool search：把入参按方法形参名拼 JSON（空参数省略 key），
     * 交给 DefaultToolExecutor 完成参数绑定/POJO 反序列化并反射调用，返回工具执行结果文本
     *
     * @param originalQuery 用于结果重排序的查询语义，必填
     * @param vectorQuery   向量语义检索语句，可为 null（省略该 key 即跳过向量路）
     * @param esQuery       ES 关键词检索词，可为 null（省略该 key 即跳过精准检索路）
     * @param filter        档案过滤条件，可为 null（省略该 key 即不过滤）
     * @return 工具返回文本；参数校验失败时返回错误文案而非抛异常
     */
    private String executeAsAgent(String originalQuery, String vectorQuery, String esQuery, ArchiveFilter filter) throws Exception {
        // 步骤 1：模拟模型只传 required 与已填写的 optional 参数，拼装 JSON 参数文本
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("originalQuery", originalQuery);
        if (vectorQuery != null) {
            arguments.put("vectorQuery", vectorQuery);
        }
        if (esQuery != null) {
            arguments.put("esQuery", esQuery);
        }
        if (filter != null) {
            arguments.set("filter", MAPPER.valueToTree(filter));
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("search")
                .arguments(arguments.toString())
                .build();

        // 步骤 2：获取 search 方法句柄并交给 langchain4j 标准工具执行器反射调用（与 AiServices 同路径）
        Method searchMethod = RagSearchTool.class.getMethod(
                "search", String.class, String.class, String.class, ArchiveFilter.class);
        return new DefaultToolExecutor(ragSearchTool, searchMethod).execute(request, null);
    }

    /**
     * 执行检索、打印结果并断言确实有命中（便捷断言辅助，供混合/单路用例复用）
     *
     * @return 检索返回的面向 LLM 文本，供调用方继续做更细断言
     */
    private String searchAndAssert(String originalQuery, String vectorQuery, String esQuery, ArchiveFilter filter) throws Exception {
        // 步骤 1：执行工具并打印返回文本便于人工核对
        String result = executeAsAgent(originalQuery, vectorQuery, esQuery, filter);
        System.out.println("========== 原始问题: " + originalQuery + " ==========");
        System.out.println(result);
        System.out.println();

        // 步骤 2：兜底断言：空结果文案不应出现，说明确有相关内容被召回
        assertFalse(result.contains("未检索到相关内容"), "应检索到相关内容，但返回：\n" + result);
        return result;
    }
}
