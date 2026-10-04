package com.liu.eka.demo;

import com.liu.eka.tool.DatabaseContextTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DatabaseContextTool 演示测试：直接调用工具方法，验证清单概览、单表语义返回与未知表名报错
 *
 * @author Luxon
 * @date 2026/09/03
 */
@SpringBootTest
class DatabaseContextToolTest {

    @Autowired
    private DatabaseContextTool databaseContextTool;

    /**
     * 演示用例 1：表名必填，留空抛 IllegalArgumentException 并提示可用表
     */
    @Test
    void blankTableNameThrows() {
        // 步骤 1：表名传空，工具应直接报错，防止 Agent 跳过语义直接写 SQL
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> databaseContextTool.searchDatabaseContext(null));
        System.out.println("========== 表名留空错误信息 ==========\n" + error.getMessage());

        // 步骤 2：断言错误信息给出可用表提示，便于 Agent 纠正入参
        assertFalse(error.getMessage().isBlank());
        assertTrue(error.getMessage().contains("ek_ar_main"));
    }

    /**
     * 演示用例 2：指定表名返回该表的语义 MD 全文
     */
    @Test
    void singleTableSemantics() throws Exception {
        // 步骤 1：查询档案主表的业务语义
        String result = databaseContextTool.searchDatabaseContext("ek_ar_main");
        System.out.println("========== ek_ar_main 语义（前 500 字） ==========\n"
                + result);

        // 步骤 2：断言返回的是该表的 MD 全文（含标题与业务含义节）
        assertTrue(result.contains("# ek_ar_main"));
        assertTrue(result.contains("## 业务含义"));
    }

    /**
     * 演示用例 3：未知表名抛 IllegalArgumentException 并提示可用表
     */
    @Test
    void unknownTableNameThrows() {
        // 步骤 1：传入不存在的表名，工具应直接报错而非返回空结果
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> databaseContextTool.searchDatabaseContext("ek_not_exist"));

        // 步骤 2：断言错误信息给出可用表提示，便于 Agent 纠正入参
        System.out.println("========== 未知表名错误信息 ==========\n" + error.getMessage());
        assertFalse(error.getMessage().isBlank());
        assertTrue(error.getMessage().contains("ek_ar_main"));
    }
}
