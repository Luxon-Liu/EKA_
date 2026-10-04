package com.liu.eka.tool;

import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import com.liu.eka.util.TableRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 数据库表结构与业务语义查询工具：给 Agent 展示库内各表的业务语义 MD 文档。
 * 表名必填，一次只返回一张表的语义全文，防止上下文爆炸；涉及多表时逐张调用。
 * 表名合法性以 TableRegistry 的动态白名单为准（库内新增表自动纳入）。
 * 在调用 sqlexecute 编写任何 SQL 之前，必须先调用本工具查清相关表的语义。
 *
 * @author Luxon
 * @date 2026/09/03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseContextTool implements EkaTool {

    /**
     * 工具注册顺序：语义查询排第二位
     *
     * @return 顺序号 2
     */
    @Override
    public int order() {
        return 2;
    }

    /**
     * 整个语义查询工具的总超时：以用户可接受的等待时长为硬上限，超出即兜断
     *
     * @return 总超时时长 3 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(3);
    }

    /**
     * MD 语义文件根目录：每张表对应一个「表名.md」文件，路径相对于应用工作目录（默认 ../MD）
     */
    @Value("${sql_tool.md-root}")
    private String mdRoot;

    /**
     * 库表白名单注册表：动态加载本库全部表名，用于表名合法性校验与清单展示
     */
    private final TableRegistry tableRegistry;

    /**
     * 工具调用统一韧性执行器：为语义文件读取加单次超时与重试，防止磁盘/网络盘 IO 卡死拖住整轮对话
     */
    private final ToolGuard toolGuard;

    /**
     * 查询指定表的业务语义 MD 全文：表名必填，一次只返回一张表，防止上下文爆炸。
     * 表名合法性以动态白名单为准；库内新增表在配好语义 MD 后即可被查询与联查，无需改代码。
     * 在调用 sqlexecute 编写任何 SQL 之前，必须先调用本工具查清相关表的业务含义、字段语义与表关系，禁止凭猜测写 SQL
     *
     * @param tableName 表名（必填，如 ek_ar_main），一次只查一张表
     * @return 该表的语义 MD 全文
     * @throws IllegalArgumentException 表名缺失、非法或不在动态白名单时抛出
     * @throws IllegalStateException    对应语义文件缺失时抛出
     */
    @Tool("""
            数据库表结构与业务语义查询工具。
            【职责范围】本工具负责返回某张数据库表的业务语义（业务含义、字段语义、表关系、常用口径 SQL），
            供组装 SQL 前确认该表的结构与含义。一次只返回一张表的语义。库内可用表如下：ek_appraisal_destroy（鉴定销毁任务）、
            ek_ar_file（原文文件）、ek_ar_main（档案主表/案卷件）、ek_ar_process（归档过程）、ek_ar_project（项目层）、
            ek_ar_side（卷内件）、ek_borrow_order（借阅单）、ek_category（门类树）、ek_process_instance（流程快照）、
            ek_transfer_batch（移交批次）、ek_transfer_detail（移交明细）、ek_user（用户）；库内新增表会自动纳入，
            以报错信息中的可用表清单为准。
            调用时必须传入一个表名，返回该表的业务语义全文（业务含义、字段语义、表关系、常用口径 SQL）。
            涉及多表时逐张调用，一次一表。在调用 sqlexecute 编写任何 SQL 之前，必须先调用本工具查清相关表的语义，禁止凭猜测写 SQL""")
    public String searchDatabaseContext(
            @P(value = "表名", required = true) String tableName) {
        // 步骤 1：表名必填，留空直接报错并提示可用表，防止 Agent 跳过语义直接写 SQL
        Set<String> knownTables = tableRegistry.knownTables();
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("必须传入一个表名"
                    + String.join("、", knownTables));
        }

        // 步骤 2：表名归一化（去空白/去 .md 后缀/转小写），并拦截路径穿越字符
        String normalized = normalizeTableName(tableName);

        // 步骤 3：动态白名单校验，未知表名直接报错并提示可用表，引导 Agent 纠正入参
        if (!knownTables.contains(normalized)) {
            throw new IllegalArgumentException("未知表名：" + tableName
                    + "。库内可用表：" + String.join("、", knownTables));
        }

        // 步骤 4：定位 MD 文件（解析为绝对路径并确认仍在语义根目录下，防止路径穿越）
        Path mdFile = Paths.get(mdRoot).toAbsolutePath().normalize()
                .resolve(normalized + ".md").normalize();
        Path root = Paths.get(mdRoot).toAbsolutePath().normalize();
        if (!mdFile.startsWith(root)) {
            throw new IllegalArgumentException("非法表名：" + tableName);
        }
        if (!Files.isRegularFile(mdFile)) {
            throw new IllegalStateException("表 " + normalized + " 的语义文件缺失：" + mdFile);
        }

        // 步骤 5：读取语义全文（独立超时与重试），文件不存在等确定性错误由分类器判定后立即返回
        return toolGuard.run("dbcontext.read", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(1000, 2, 2500),
                () -> Files.readString(mdFile, StandardCharsets.UTF_8));
    }

    /**
     * 表名归一化：去首尾空白、兼容模型误带的 .md 后缀、统一小写，并拦截路径穿越字符
     *
     * @param tableName 原始表名入参
     * @return 归一化后的小写表名
     */
    private static String normalizeTableName(String tableName) {
        // 去空白与 .md 后缀，统一小写，保证大小写/后缀写法的容错
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".md")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        // 拦截路径穿越与分隔符，只允许纯表名通过
        if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("非法表名：" + tableName);
        }
        return normalized;
    }
}
