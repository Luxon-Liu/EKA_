package com.liu.eka.util;

import com.liu.eka.mapper.TableListMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 库表白名单注册表：基于 MyBatis 从 information_schema 动态加载本库全部表名，
 * 替代写死的表集合；库内新增表最多经一个缓存周期后自动纳入，无需改代码。
 * 供 searchDatabaseContext 与 sqlexecute 两个工具做表级合法性校验
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableRegistry {

    /**
     * 库表清单查询器：直查 information_schema，不依赖业务实体
     */
    private final TableListMapper tableListMapper;

    /**
     * 本库名：只加载该库的表清单，与 sqlexecute 的跨库校验口径一致
     */
    @Value("${sql_tool.database:enterprise_knowledge_agent}")
    private String database;

    /**
     * 白名单缓存有效期：5 分钟，兼顾新增表感知速度与查询开销
     */
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * 白名单缓存：小写表名不可修改集合，volatile 保证多线程可见性
     */
    private volatile Set<String> cachedTables;

    /**
     * 缓存到期时间戳（毫秒）
     */
    private volatile long expireAt = 0;

    /**
     * 返回本库全部表名（小写、不可修改）：缓存有效期内直接返回，过期则同步刷新
     *
     * @return 本库表名集合
     */
    public Set<String> knownTables() {
        // 步骤 1：缓存命中直接返回，无锁快路径
        Set<String> hit = cachedTables;
        if (hit != null && System.currentTimeMillis() < expireAt) {
            return hit;
        }
        // 步骤 2：缓存未命中则加锁刷新（双检防止并发重复查库）
        synchronized (this) {
            if (cachedTables != null && System.currentTimeMillis() < expireAt) {
                return cachedTables;
            }
            return reload();
        }
    }

    /**
     * 强制刷新白名单：跳过缓存直接查库，用于建表后立刻生效的场景
     *
     * @return 刷新后的本库表名集合
     */
    public Set<String> refresh() {
        synchronized (this) {
            return reload();
        }
    }

    /**
     * 查库重载白名单：表名统一转小写存放，与校验侧的大小写归一保持一致
     *
     * @return 重载后的本库表名集合
     */
    private Set<String> reload() {
        // 步骤 1：直查 information_schema 取本库全部表名
        List<String> names = tableListMapper.listTableNames(database);
        // 步骤 2：转小写后装入不可修改集合并刷新缓存
        Set<String> tables = new HashSet<>(names.size() * 2);
        for (String name : names) {
            if (name != null) {
                tables.add(name.toLowerCase(Locale.ROOT));
            }
        }
        cachedTables = Collections.unmodifiableSet(tables);
        expireAt = System.currentTimeMillis() + CACHE_TTL_MILLIS;
        return cachedTables;
    }
}
