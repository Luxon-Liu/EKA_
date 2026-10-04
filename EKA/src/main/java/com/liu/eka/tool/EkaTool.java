package com.liu.eka.tool;

import java.time.Duration;

/**
 * Agent 工具标记接口：tool 包下所有 Agent 工具类实现此接口，
 * ArchiveAgentFactory 按类型批量注入后扫描各实现类的全部 @Tool 方法自动注册。
 * 新增工具只需新建实现类，无需改动工厂代码；注册顺序由 order 决定（越小越先展示给模型）。
 *
 * @author Luxon
 * @date 2026/09/04
 */
public interface EkaTool {

    /**
     * 工具注册顺序：越小越先展示给模型（如 RAG 检索优先于查库）
     *
     * @return 顺序号
     */
    int order();

    /**
     * 整个工具方法的总超时：外层执行器据此限制累积耗时（内层各步骤的重试管不了累积），
     * 取值应不小于该工具内各次外部调用预算的总时长之和，否则外层会提前掐断内层重试
     *
     * @return 总超时时长
     */
    Duration totalTimeout();
}
