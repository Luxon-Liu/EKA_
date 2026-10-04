package com.liu.eka.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.liu.eka.entity.conversation.AiChatFile;
import com.liu.eka.entity.conversation.ChatFileVO;
import com.liu.eka.entity.conversation.FileExportVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;

/**
 * 会话文件服务的接口说明：用户上传、AI 生成、列表查询与占位符锚点的统一入口，
 * 元数据落 ai_chat_file 表，文件内容落磁盘目录
 *
 * @author Luxon
 * @date 2026/09/21
 */
public interface ChatFileService extends IService<AiChatFile> {

    /**
     * 上传用户源文件：校验类型与数量后，元数据落表、内容以文件 ID 命名落盘
     *
     * @param sessionId 会话 ID（即 memoryId），不允许为空
     * @param files     上传的文件列表，单次最多 5 个
     * @return 落库后的文件元数据列表（含文件 ID，供前端发消息时透传）
     */
    List<ChatFileVO> upload(Long sessionId, List<MultipartFile> files);

    /**
     * 查询某会话的全部有效文件（上传 + 生成），按最后修改时间倒序
     *
     * @param sessionId 会话 ID
     * @return 文件元数据列表，供前端占位符卡片渲染
     */
    List<ChatFileVO> listBySession(Long sessionId);

    /**
     * 分页查询会话文件：关键词按文件名或用途描述模糊匹配，
     * 过滤、排序与分页全部下推到数据库，避免把整个会话的文件捞进内存
     *
     * @param sessionId 会话 ID
     * @param keyword   关键词，可为空表示不过滤
     * @param pageNum   页码，从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果（records 已转对外视图，含 total/current/pages 元信息）
     */
    IPage<ChatFileVO> listPage(Long sessionId, String keyword, int pageNum, int pageSize);

    /**
     * 按 ID 集合批量取文件视图（发消息时解析前端透传的上传文件清单）
     *
     * @param fileIds 文件 ID 集合，允许为空
     * @return 文件视图列表；入参为空时返回空列表
     */
    List<ChatFileVO> listVOByIds(Collection<Long> fileIds);

    /**
     * 批量回填占位符锚点：把指定文件的 anchor_message_id 更新为给定消息 ID
     *
     * @param fileIds         文件 ID 集合，允许为空
     * @param anchorMessageId 锚定的消息 ID，为空时不做任何更新
     */
    void anchorFiles(Collection<Long> fileIds, Long anchorMessageId);

    /**
     * 按文件 ID 定位当前会话内的有效文件（AI 按 ID 引用文件时使用）
     *
     * @param sessionId 会话 ID，用于校验文件归属，防止跨会话访问
     * @param fileId    文件 ID
     * @return 文件元数据行；文件不存在、已删除或不属于该会话时返回 null
     */
    AiChatFile findOwned(Long sessionId, Long fileId);

    /**
     * 保存 AI 生成的 HTML 产物：fileId 为空则新建，非空则覆盖该文件（仅 AI 生成的文件可被覆盖）
     *
     * @param sessionId   会话 ID
     * @param fileId      要覆盖的文件 ID，为空表示新建
     * @param fileName    展示文件名（含 .html 扩展名）；覆盖时为空表示沿用原文件名
     * @param html        HTML 正文
     * @param description 用途描述，可为空（覆盖时为空则保留原描述）
     * @return 落库后的文件元数据行
     */
    AiChatFile saveGenerated(Long sessionId, Long fileId, String fileName, String html, String description);

    /**
     * 对 AI 生成的 HTML 文件做局部替换：把正文中唯一出现的 oldString 原样替换为 newString，
     * 只改写命中处，避免整篇重写带来的 token 开销与内容漂移
     *
     * @param sessionId 会话 ID
     * @param fileId    要修改的文件 ID，必须属于该会话且为 AI 生成的 HTML
     * @param oldString 被替换的原文片段，不允许为空，且必须在文件中唯一出现
     * @param newString 替换后的新片段，允许为空串（表示删除该片段）
     * @return 落库后的文件元数据行
     */
    AiChatFile editGenerated(Long sessionId, Long fileId, String oldString, String newString);

    /**
     * 读取生成文件的 HTML 内容：供前端 iframe 预览
     *
     * @param fileId 文件 ID
     * @return HTML 文本
     */
    String previewHtml(Long fileId);

    /**
     * 导出生成文件为 PDF：读回 HTML 原文并渲染成 PDF 字节，渲染在内存中完成、不落盘
     *
     * @param fileId 文件 ID，须为 AI 生成的 HTML 产物
     * @return 导出结果（对用户可见的下载文件名 + PDF 字节内容）
     */
    FileExportVO exportPdf(Long fileId);

    /**
     * 清理会话的文件数据：逻辑删除该会话全部文件元数据，并移除磁盘上对应的会话目录。
     * 供删除会话时一并调用，避免残留无主文件
     *
     * @param sessionId 会话 ID，不允许为空
     */
    void deleteBySession(Long sessionId);
}
