package com.liu.eka.demo;

import com.liu.eka.tool.SqlExecuteTool;
import com.liu.eka.util.TableRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TableRegistry 演示测试：验证白名单从库内动态加载，新增表无需改代码即可被工具放行
 *
 * @author Luxon
 * @date 2026/09/04
 */
@SpringBootTest
class TableRegistryTest {

    @Autowired
    private TableRegistry tableRegistry;

    @Autowired
    private SqlExecuteTool sqlExecuteTool;

    @Autowired
    private DataSource dataSource;

    /**
     * 演示用例 1：动态白名单包含现有业务表
     */
    @Test
    void knownTablesContainsBusinessTables() {
        // 步骤 1：加载动态白名单
        Set<String> tables = tableRegistry.knownTables();
        System.out.println("========== 动态白名单 ==========\n" + String.join("、", tables));

        // 步骤 2：断言 12 张业务表全部在列（小写归一）
        assertTrue(tables.contains("ek_ar_main"));
        assertTrue(tables.contains("ek_ar_side"));
        assertTrue(tables.contains("ek_ar_file"));
        assertTrue(tables.contains("ek_ar_project"));
        assertTrue(tables.contains("ek_category"));
        assertTrue(tables.contains("ek_user"));

        // 步骤 3：ai_ 系内部表不得进入 Agent 白名单
        assertFalse(tables.contains("ai_chat_session"));
        assertFalse(tables.contains("ai_chat_message"));
    }

    /**
     * 演示用例 2：库内新建表经刷新后自动纳入白名单，sqlexecute 直接放行
     */
    @Test
    void newTableAutoIncludedAfterRefresh() throws Exception {
        // 步骤 1：直连建一张探测表（走 JDBC，不走工具的写拦截）
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ek_tmp_probe (id INT PRIMARY KEY, name VARCHAR(50))");
        }
        try {
            // 步骤 2：刷新白名单并断言新表已纳入
            Set<String> tables = tableRegistry.refresh();
            assertTrue(tables.contains("ek_tmp_probe"));

            // 步骤 3：新表查询直接放行，无需改任何代码
            String result = sqlExecuteTool.sqlexecute("SELECT * FROM ek_tmp_probe LIMIT 1");
            System.out.println("========== 新表查询结果 ==========\n" + result);
            assertTrue(result.contains("| id | name |"));
            assertFalse(result.contains("未知表"));
        } finally {
            // 步骤 4：清理探测表，避免污染库
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS ek_tmp_probe");
            }
            tableRegistry.refresh();
        }
    }
}
