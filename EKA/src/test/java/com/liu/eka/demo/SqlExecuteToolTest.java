package com.liu.eka.demo;

import com.liu.eka.tool.SqlExecuteTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlExecuteTool 演示测试：直接调用工具方法，验证正常查询放行与四类越权拦截
 *
 * @author Luxon
 * @date 2026/09/03
 */
@SpringBootTest
class SqlExecuteToolTest {

    @Autowired
    private SqlExecuteTool sqlExecuteTool;

    /**
     * 演示用例 1：正常 SELECT 查询放行，返回 Markdown 表格
     */
    @Test
    void selectAllowed() throws Exception {
        // 步骤 1：执行单表只读查询
        String result = sqlExecuteTool.sqlexecute("SELECT id, title, status FROM ek_ar_main ORDER BY id LIMIT 3");
        System.out.println("========== 正常查询结果 ==========\n" + result);

        // 步骤 2：断言返回 Markdown 表格（含表头与行数说明）
        assertTrue(result.contains("| id | title | status |"));
        assertTrue(result.contains("共 3 行"));
    }

    /**
     * 演示用例 2：写操作（UPDATE）直接拒绝
     */
    @Test
    void rejectWriteOperation() {
        // 步骤 1：传入写语句，工具应拒绝而非执行
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sqlExecuteTool.sqlexecute("UPDATE ek_user SET username = 'x' WHERE id = '1'"));
        System.out.println("========== 写操作拦截信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息指明只允许查询
        assertTrue(error.getMessage().contains("只允许"));
    }

    /**
     * 演示用例 3：白名单外的表（information_schema）直接拒绝
     */
    @Test
    void rejectUnknownTable() {
        // 步骤 1：查询系统库表，工具应拒绝
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sqlExecuteTool.sqlexecute("SELECT * FROM information_schema.tables LIMIT 1"));
        System.out.println("========== 白名单外表拦截信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息指明不允许跨库（information_schema 是库.表形式，走跨库拦截分支）
        assertTrue(error.getMessage().contains("跨库"));
    }

    /**
     * 演示用例 4：多语句堆叠直接拒绝
     */
    @Test
    void rejectMultiStatement() {
        // 步骤 1：传入堆叠语句，第二条是写操作，工具应拒绝整条执行
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sqlExecuteTool.sqlexecute("SELECT id FROM ek_ar_main LIMIT 1; DELETE FROM ek_ar_main"));
        System.out.println("========== 多语句拦截信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息指明单条限制（写关键字或多语句任一命中即算拦截成功）
        assertTrue(error.getMessage().contains("单条") || error.getMessage().contains("只允许"));
    }

    /**
     * 演示用例 6：本库内但不在白名单的表同样拒绝，并提示可用表
     */
    @Test
    void rejectTableOutsideWhitelist() {
        // 步骤 1：查询白名单外的表名，工具应拒绝
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sqlExecuteTool.sqlexecute("SELECT * FROM ek_not_exist LIMIT 1"));
        System.out.println("========== 白名单外表拦截信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息给出可用表提示
        assertTrue(error.getMessage().contains("可用表"));
    }

    /**
     * 演示用例 5：显式跨库查询直接拒绝
     */
    @Test
    void rejectCrossDatabase() {
        // 步骤 1：查询其他库的表，工具应拒绝
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sqlExecuteTool.sqlexecute("SELECT * FROM mysql.user LIMIT 1"));
        System.out.println("========== 跨库拦截信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息指明不允许跨库
        assertTrue(error.getMessage().contains("跨库"));
    }

    /**
     * 演示用例 7：多表 JOIN 联查放行（参与的表全在白名单内）
     */
    @Test
    void joinAcrossTablesAllowed() throws Exception {
        // 步骤 1：卷内件关联档案主表，查卷名与件名（列别名避免两表 title 重名）
        String result = sqlExecuteTool.sqlexecute(
                "SELECT m.title AS 卷名, s.title AS 件名 FROM ek_ar_main m "
                        + "JOIN ek_ar_side s ON s.main_id = m.id "
                        + "WHERE m.del_flag = 0 ORDER BY m.id LIMIT 3");
        System.out.println("========== 联查结果 ==========\n" + result);

        // 步骤 2：断言返回联查后的 Markdown 表格
        assertTrue(result.contains("| 卷名 | 件名 |"));
        assertTrue(result.contains("共 3 行"));
    }

    /**
     * 演示用例 8：CTE（WITH…AS…）联查放行，CTE 名不视为真实表
     */
    @Test
    void cteQueryAllowed() throws Exception {
        // 步骤 1：先用 CTE 圈定有效档案，再关联卷内件（FROM m 中的 m 是 CTE 名，应跳过白名单校验）
        String result = sqlExecuteTool.sqlexecute(
                "WITH m AS (SELECT id, title FROM ek_ar_main WHERE del_flag = 0) "
                        + "SELECT m.title AS 卷名, s.title AS 件名 FROM m "
                        + "JOIN ek_ar_side s ON s.main_id = m.id LIMIT 3");
        System.out.println("========== CTE 联查结果 ==========\n" + result);

        // 步骤 2：断言返回联查后的 Markdown 表格
        assertTrue(result.contains("| 卷名 | 件名 |"));
        assertTrue(result.contains("共 3 行"));
    }
}
