package com.liu.eka.tool.resilience;

/**
 * 工具外部调用的重试策略：声明这次调用是否幂等、失败后能否安全重试。
 * 取值由「操作本身是不是幂等」决定，属于代码性质而非运维参数，因此随调用点在代码中传入，
 * 不放进 application.yml（yml 只承载单次超时、重试次数、总时长等数值预算）
 *
 * @author Luxon
 * @date 2026/09/18
 */
public enum ToolPolicy {

    /** 可重试：幂等的读操作（向量检索、关键词检索、重排、只读 SQL、读语义文件），失败后按预算重试 */
    TRANSIENT,

    /** 不可重试：非幂等或确定性失败的操作，只做单次超时，失败立即返回 */
    NONE
}
