package com.liu.eka.util;

/**
 * 文本缩写工具：超长文本截断前部，防止长文本污染日志、错误文案与前端上下文
 *
 * @author Luxon
 * @date 2026/09/04
 */
public final class Texts {

    /** 禁止实例化，纯静态工具类 */
    private Texts() {
    }

    /**
     * 通用截断：超长时保留前部并标注总长度
     *
     * @param text      待截断文本，可为 null
     * @param maxLength 最大保留字符数
     * @return null 原样返回 null，否则返回截断后的文本
     */
    public static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...（共 " + text.length() + " 字已截断）";
    }

    /**
     * 紧凑截断：先去首尾空白并把连续空白压成单个空格再截断，适用于 SQL 等可压缩文本
     *
     * @param text      待截断文本，可为 null
     * @param maxLength 最大保留字符数
     * @return null 原样返回 null，否则返回压空白并截断后的文本
     */
    public static String abbreviateCompact(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String compact = text.trim().replaceAll("\\s+", " ");
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "...";
    }
}
