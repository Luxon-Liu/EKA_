package com.liu.eka.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库表清单查询接口：从 information_schema 动态读取本库全部业务表名（仅 ek_ 前缀，
 * ai_ 系为 AI 内部表、不进 Agent 白名单），供表级白名单（TableRegistry）使用；
 * 库内新增业务表无需改代码即可自动纳入
 *
 * @author Luxon
 * @date 2026/09/04
 */
public interface TableListMapper {

    /**
     * 查询指定库下的全部业务表名
     *
     * @param schema 库名（如 enterprise_knowledge_agent）
     * @return 该库下 ek_ 前缀表名（原始大小写）
     */
    @Select("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
            + "WHERE TABLE_SCHEMA = #{schema} AND TABLE_NAME LIKE 'ek\\_%'")
    List<String> listTableNames(@Param("schema") String schema);
}
