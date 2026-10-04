package com.liu.eka.entity.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话文件行的实体说明（对应 ai_chat_file 表）：用户上传的源文件与 AI 生成的产物文件统一存放，
 * 一条文件一行；磁盘上以主键 id 命名，前端占位符卡片按 anchorMessageId 挂到对应消息下
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_chat_file")
public class AiChatFile {

    /** 文件 ID（自增主键，同时作为磁盘文件名） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID（ai_chat_session.id） */
    private Long sessionId;

    /** 展示文件名（含扩展名，会话内唯一） */
    private String fileName;

    /** 磁盘相对路径（相对 file.session-root，以 fileId 命名） */
    private String storedPath;

    /** 文件来源：1 用户上传 / 2 AI 生成（对应 FileKind 编码） */
    private Integer kind;

    /** 文件格式：1 docx / 2 xlsx / 3 pdf / 4 html（对应 FileFormat 编码） */
    private Integer format;

    /** 文件用途描述（上传时为空，生成时 AI 自报，供 list_files 检索定位） */
    private String description;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 占位符锚定的消息 ID（上传=上传那条，生成=最后修改那条） */
    private Long anchorMessageId;

    /** 删除标记：0有效 1删除 */
    private Integer delFlag;

    /** 创建时间（数据库自动维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动维护，卡片展示此时间） */
    private LocalDateTime updatedAt;
}
