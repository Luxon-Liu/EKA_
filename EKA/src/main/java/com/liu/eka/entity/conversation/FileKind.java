package com.liu.eka.entity.conversation;

/**
 * 会话文件来源枚举：区分用户上传的源文件与 AI 生成的产物文件，
 * 落库时存数值编码（ai_chat_file.kind 列，tinyint），不落英文枚举名
 *
 * @author Luxon
 * @date 2026/09/21
 */
public enum FileKind {

    /** 用户上传的源文件，编码 1：只读材料，不被 edit，通常只在用户那条消息下出现一次 */
    SOURCE(1),

    /** AI 生成的产物文件，编码 2：可变产物，会被反复 write/edit，占位符跟随最后一次修改移动 */
    GENERATED(2);

    /** 落库数值编码 */
    private final int code;

    /**
     * 构造枚举：绑定该来源的落库编码
     *
     * @param code 落库数值编码
     */
    FileKind(int code) {
        this.code = code;
    }

    /**
     * 取落库编码
     *
     * @return 该来源在 kind 列中的数值
     */
    public int code() {
        return code;
    }

    /**
     * 按落库编码反查来源枚举：读取历史数据时把 tinyint 还原为枚举
     *
     * @param code 落库数值编码，允许为空
     * @return 匹配的来源枚举；编码为空或未知时返回 null
     */
    public static FileKind ofCode(Integer code) {
        // 参数兜底：编码为空直接判为未知
        if (code == null) {
            return null;
        }
        // 逐个比对枚举绑定的编码
        for (FileKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        return null;
    }
}
