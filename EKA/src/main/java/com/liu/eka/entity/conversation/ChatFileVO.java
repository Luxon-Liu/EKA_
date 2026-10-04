package com.liu.eka.entity.conversation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话文件对外视图：只暴露前端渲染占位符卡片所需字段，
 * 屏蔽磁盘路径等内部信息（storedPath 不出接口）
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Data
@Builder
public class ChatFileVO {

    /** 文件 ID */
    private Long id;

    /** 展示文件名 */
    private String fileName;

    /** 文件来源：1 用户上传 / 2 AI 生成（对应 FileKind 编码） */
    private Integer kind;

    /** 文件格式：1 docx / 2 xlsx / 3 pdf / 4 html（对应 FileFormat 编码） */
    private Integer format;

    /** 用途描述（上传时为空，生成时 AI 自报） */
    private String description;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 占位符锚定的消息 ID（前端据此把卡片挂到对应消息下） */
    private Long anchorMessageId;

    /** 最后修改时间（卡片展示此时间，由数据库在插入/更新时自动维护） */
    private LocalDateTime updatedAt;
}
