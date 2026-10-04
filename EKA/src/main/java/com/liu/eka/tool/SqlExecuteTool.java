package com.liu.eka.tool;

import com.liu.eka.tool.resilience.ToolBudget;
import com.liu.eka.tool.resilience.ToolGuard;
import com.liu.eka.tool.resilience.ToolPolicy;
import com.liu.eka.util.TableRegistry;
import com.liu.eka.util.Texts;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 执行工具：Agent 自己组装 SELECT 查询语句并执行，返回 Markdown 表格结果。
 * 越权防护只允许单条 SELECT/WITH 查询：非查询语句、多语句堆叠、跨库表、白名单外表一律拒绝。
 * 支持多表 JOIN 联查、CTE（WITH…AS…）与子查询：参与联查的每一张真实表都必须在白名单内，
 * CTE 名称与派生表别名不视为表名（CTE 名自动识别并跳过校验）。
 * 调用前必须先调 searchDatabaseContext 查清相关表的业务语义，禁止凭猜测组装 SQL。
 *
 * @author Luxon
 * @date 2026/09/03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlExecuteTool implements EkaTool {

    /**
     * 工具注册顺序：SQL 执行排第三位
     *
     * @return 顺序号 3
     */
    @Override
    public int order() {
        return 3;
    }

    /**
     * 整个 SQL 工具的总超时：以用户可接受的等待时长为硬上限，单次 JDBC 执行的重试在此预算内尽力而为
     *
     * @return 总超时时长 6 秒
     */
    @Override
    public Duration totalTimeout() {
        return Duration.ofSeconds(6);
    }

    /**
     * 数据源：JDBC URL 已固定本库，账号本身也只应具备读权限，构成 DB 层的兜底防线
     */
    private final DataSource dataSource;

    /**
     * 库表白名单注册表：动态加载本库全部表名，用于 FROM/JOIN 表引用的合法性校验
     */
    private final TableRegistry tableRegistry;

    /**
     * 工具调用统一韧性执行器：为整段 JDBC 执行加单次超时与重试（native queryTimeout 仍作底层兜底）
     */
    private final ToolGuard toolGuard;

    /**
     * 本库名：SQL 中显式跨库（db.table 且 db 非本库）直接拒绝
     */
    @Value("${sql_tool.database:enterprise_knowledge_agent}")
    private String database;

    /**
     * 单次查询最多返回行数：超限截断并注明，防止结果撑爆上下文
     */
    @Value("${sql_tool.max-rows:100}")
    private int maxRows;

    /**
     * 单次查询超时秒数：慢查询由 JDBC 直接掐断
     */
    @Value("${sql_tool.timeout-seconds:4}")
    private int timeoutSeconds;

    /**
     * 写操作黑名单：出现在清洗后 SQL 中的单词即拒绝（字符串字面量与注释已预先剥离，不会误伤）
     */
    private static final Pattern WRITE_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|create|alter|truncate|replace|grant|revoke|call|set\\b|handler|lock|unlock|load|outfile|dumpfile|load_file|into)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 表引用提取：匹配 FROM/JOIN 后的「表」或「库.表」（支持反引号），派生表子查询内的 FROM/JOIN 同样命中
     */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?:from|join)\\s+(`?)([a-zA-Z0-9_]+)\\1(?:\\s*\\.\\s*(`?)([a-zA-Z0-9_]+)\\3)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * CTE 名称提取：匹配「名称 AS (」定义形式，WITH 查询中定义的 CTE 名在表校验时跳过。
     * 只在 WITH 开头的语句上提取；列别名 AS 后极少直接跟左括号，误伤风险可忽略
     */
    private static final Pattern CTE_DEF = Pattern.compile(
            "([a-zA-Z0-9_]+)\\s+as\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * 执行 Agent 组装的只读 SQL：仅允许单条 SELECT/WITH 查询，返回 Markdown 表格结果。
     * 支持多表 JOIN 联查、CTE 与子查询（参与的真实表必须全在动态白名单内）；
     * 调用前必须先调 searchDatabaseContext 查清相关表的业务语义；除查询外的任何写操作、
     * 跨库表、白名单外表都会被拒绝
     *
     * @param sql Agent 组装的查询语句（必填，仅允许单条 SELECT/WITH，只查动态白名单内的表）
     * @return Markdown 表格形式的查询结果（超 maxRows 行截断并注明）
     * @throws IllegalArgumentException SQL 越权校验不通过时抛出
     */
    @Tool("""
            只读 SQL 执行工具，只允许 SELECT 查询。
            【职责范围】本工具负责回答数据库里可统计、可枚举的结构化事实类问题：档案数量、有哪些卷/件、
            归档状态、某项目下的档案清单、按年度/门类分布等。
            库内可用表如下（库内新增表会自动纳入白名单，以报错信息中的可用表清单为准）：ek_appraisal_destroy、ek_ar_file、ek_ar_main、
            ek_ar_process、ek_ar_project、ek_ar_side、ek_borrow_order、ek_category、ek_process_instance、
            ek_transfer_batch、ek_transfer_detail、ek_user。
            支持多表 JOIN 联查、CTE（WITH…AS…）与子查询，但参与联查的每一张真实表都必须在白名单之内。
            【前置要求】调用本工具前，必须先调用 searchDatabaseContext 查清所涉及数据库表的业务语义（字段含义、表关系、常用口径），理解后再组装 SQL，禁止凭猜测直接写 SQL。
            除查询外的一切操作
            （INSERT/UPDATE/DELETE/DROP/CREATE/ALTER 等写操作、多语句堆叠、跨库表、白名单外的表如
            information_schema）都会被拒绝。返回 Markdown 表格结果，超限行数自动截断""")
    public String sqlexecute(
            @P(value = "Agent 组装的查询语句，仅允许单条 SELECT/WITH，只查白名单内的表", required = true) String sql) {
        // 步骤 1：SQL 越权校验（只读/单条/白名单表），不通过直接抛错由 Agent 纠正语句
        validateSelectOnly(sql);

        // 步骤 2：整段 JDBC 执行包进统一韧性执行器（连接获取 + 查询 + 结果读取统算一次外部调用），
        //         单次超时与重试由 ToolGuard 统一调度，语句级 queryTimeout 作为数据库侧兜底
        QueryResult result = toolGuard.run("sql.execute", ToolPolicy.TRANSIENT, ToolBudget.ofMillis(4000, 2, 8500),
                () -> executeReadOnly(sql));

        // 步骤 3：渲染 Markdown 表格返回给模型
        return renderMarkdown(result.columns(), result.rows(), result.truncated());
    }

    /**
     * 以只读连接执行单条查询并读取结果集：连接置只读 + 语句超时双保险，
     * 行数超过 maxRows 即停止读取并标记截断，避免大结果集撑爆上下文
     *
     * @param sql 已通过越权校验的单条查询语句
     * @return 列名、数据行与是否截断
     * @throws SQLException 数据库连接或执行失败时抛出
     */
    private QueryResult executeReadOnly(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    // 步骤 1：按列顺序读取列名
                    ResultSetMetaData meta = resultSet.getMetaData();
                    List<String> columns = new ArrayList<>(meta.getColumnCount());
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    // 步骤 2：逐行读取，超过上限即停止并标记截断
                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (resultSet.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(columns.get(i - 1), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    return new QueryResult(columns, rows, truncated);
                }
            }
        }
    }

    /**
     * 查询结果载体：列名、已读取的数据行与是否因超限被截断
     *
     * @param columns   列名（按查询顺序）
     * @param rows      数据行
     * @param truncated 是否被截断
     * @author Luxon
     * @date 2026/09/18
     */
    private record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }

    /**
     * SQL 越权校验：只放行单条 SELECT/WITH 只读查询；写操作、多语句、跨库、白名单外表一律拒绝
     *
     * @param sql Agent 组装的原始 SQL
     */
    private void validateSelectOnly(String sql) {
        // 步骤 1：空语句直接拒绝
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空，只允许单条 SELECT 查询");
        }

        // 步骤 2：剥离注释与字符串字面量后再做判断，防止注释/字符串藏关键字绕过
        String cleaned = stripLiteralsAndComments(sql).trim();

        // 步骤 3：只允许 SELECT/WITH 开头（含左括号包裹的子查询形式）
        String head = cleaned.replaceAll("^[\\s(]+", "");
        if (!head.regionMatches(true, 0, "SELECT", 0, 6)
                && !head.regionMatches(true, 0, "WITH", 0, 4)) {
            throw new IllegalArgumentException("只允许 SELECT 查询，拒绝执行：" + Texts.abbreviateCompact(sql, 120));
        }

        // 步骤 4：写操作关键字黑名单，命中即拒绝
        if (WRITE_KEYWORDS.matcher(cleaned).find()) {
            throw new IllegalArgumentException("只允许查询操作，检测到写操作关键字，拒绝执行：" + Texts.abbreviateCompact(sql, 120));
        }

        // 步骤 5：多语句堆叠拒绝（分号只允许出现在末尾且最多一个）
        String noTrailingSemi = cleaned.replaceAll(";\\s*$", "");
        if (noTrailingSemi.contains(";")) {
            throw new IllegalArgumentException("一次只允许执行单条 SQL，拒绝多语句堆叠");
        }

        // 步骤 6：逐个校验 FROM/JOIN 引用的表（库前缀非本库即跨库；表名不在白名单即拒绝；
        // CTE 名称与派生表别名不是真实表，跳过校验；多表 JOIN 时每个引用逐个过白名单）
        Set<String> cteNames = extractCteNames(head, cleaned);
        Matcher tables = TABLE_REF.matcher(cleaned);
        boolean foundTable = false;
        while (tables.find()) {
            foundTable = true;
            String first = tables.group(2);
            String second = tables.group(4);
            String tableName = second != null ? second : first;
            // 显式库前缀必须等于本库，否则为跨库查询
            if (second != null && !first.equalsIgnoreCase(database)) {
                throw new IllegalArgumentException("不允许跨库查询：" + first + "，只能查本库 " + database);
            }
            // CTE 内定义的名称不是真实表，直接跳过（只处理无库前缀的裸引用）
            if (second == null && cteNames.contains(first.toLowerCase())) {
                continue;
            }
            // 表名必须在动态白名单内（库内新增表经 TableRegistry 自动纳入）
            if (!tableRegistry.knownTables().contains(tableName.toLowerCase())) {
                throw new IllegalArgumentException("未知表或不允许查询的表：" + tableName
                        + "。可用表：" + String.join("、", tableRegistry.knownTables()));
            }
        }
        // SELECT 查询必然引用表，无表引用视为异常语句一并拒绝
        if (!foundTable) {
            throw new IllegalArgumentException("SQL 未引用任何可用表，拒绝执行：" + Texts.abbreviateCompact(sql, 120));
        }
    }

    /**
     * 提取 WITH 查询中定义的 CTE 名称：只有 WITH 开头的语句才提取，避免普通查询中的列别名误伤；
     * 多个 CTE（逗号分隔）逐个收集，返回小写集合供表校验时跳过
     *
     * @param head    清洗后 SQL 去掉前导空白与括号的开头（已在校验步骤 3 中计算）
     * @param cleaned 剥离注释与字面量后的完整 SQL
     * @return CTE 名称小写集合；非 WITH 查询返回空集合
     */
    private static Set<String> extractCteNames(String head, String cleaned) {
        // 非 WITH 查询不可能定义 CTE，直接返回空集合
        Set<String> names = new HashSet<>();
        if (!head.regionMatches(true, 0, "WITH", 0, 4)) {
            return names;
        }
        // 逐个收集「名称 AS (」形式的定义（WITH a AS (...), b AS (...) 都会命中）
        Matcher defs = CTE_DEF.matcher(cleaned);
        while (defs.find()) {
            names.add(defs.group(1).toLowerCase());
        }
        return names;
    }

    /**
     * 剥离 SQL 中的注释与字符串字面量：注释直接删除，字面量内容替换为空格（保留引号对齐），
     * 使后续关键字/分号/表名判断不受注释藏词与字符串干扰
     *
     * @param sql 原始 SQL
     * @return 清洗后的 SQL
     */
    private static String stripLiteralsAndComments(String sql) {
        // 状态机单遍扫描：字符串外遇到注释起始符则跳过注释段，引号内内容全部视为空格
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 行注释 -- 与 #：跳到行尾
            if ((c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                    || c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            // 块注释 /* */：跳到闭合
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                out.append(' ');
                continue;
            }
            // 字符串/标识符引号：内容替换为空格（处理转义与 '' 连写）
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        if (quote == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 查询结果渲染为 Markdown 表格：NULL 显示为 NULL，单元格内换行与竖线做转义防止破表
     *
     * @param columns 列名（按查询顺序）
     * @param rows    数据行
     * @param truncated 是否因超限被截断
     * @return Markdown 表格文本
     */
    private String renderMarkdown(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        // 步骤 1：拼接表头行与分隔行
        StringBuilder markdown = new StringBuilder();
        markdown.append("| ").append(String.join(" | ", columns)).append(" |\n");
        markdown.append("|").append(" --- |".repeat(columns.size())).append("\n");
        // 步骤 2：逐行拼接单元格（NULL 显示 NULL，换行压成空格，竖线转义）
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String column : columns) {
                cells.add(formatCell(row.get(column)));
            }
            markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        // 步骤 3：注明行数与截断情况
        markdown.append("\n共 ").append(rows.size()).append(" 行");
        if (truncated) {
            markdown.append("（已截断，最多返回 ").append(maxRows).append(" 行，请加 WHERE/LIMIT 缩小范围）");
        }
        return markdown.toString();
    }

    /**
     * 单个单元格格式化：null 显示为 NULL，换行压成空格，竖线转义避免破坏表格结构
     *
     * @param value 单元格原始值
     * @return 转义后的单元格文本
     */
    private static String formatCell(Object value) {
        // 空值显式标 NULL，方便 Agent 区分空串与缺失
        if (value == null) {
            return "NULL";
        }
        return value.toString().replace("\r", " ").replace("\n", " ").replace("|", "\\|");
    }

}
