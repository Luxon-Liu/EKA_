package com.liu.eka.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liu.eka.common.SessionGuard;
import com.liu.eka.common.UserContext;
import com.liu.eka.entity.conversation.AiChatFile;
import com.liu.eka.entity.conversation.ChatFileVO;
import com.liu.eka.entity.conversation.FileExportVO;
import com.liu.eka.entity.conversation.FileFormat;
import com.liu.eka.entity.conversation.FileKind;
import com.liu.eka.mapper.AiChatFileMapper;
import com.liu.eka.service.ChatFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 会话文件服务的实现说明：用户上传、AI 生成、列表查询与占位符锚点的统一入口，
 * 元数据落 ai_chat_file 表，文件内容落 ChatFileStorageService 管理的磁盘目录
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFileServiceImpl extends ServiceImpl<AiChatFileMapper, AiChatFile> implements ChatFileService {

    /** 单次上传允许的最大文件数 */
    private static final int MAX_UPLOAD_COUNT = 5;

    /** 允许上传的源文件格式白名单：仅 docx/xlsx/pdf 三种原始文档，生成产物 HTML 不允许再上传 */
    private static final Set<FileFormat> UPLOAD_FORMATS =
            EnumSet.of(FileFormat.DOCX, FileFormat.XLSX, FileFormat.PDF);

    /** 磁盘存储服务：路径计算、落盘与读取 */
    private final ChatFileStorageService storage;

    /** PDF 渲染服务：把生成产物的 HTML 原文渲染成 PDF 字节 */
    private final PdfRenderService pdfRenderService;

    /** 本轮文件变更追踪器：记录本轮被写/改的文件，供轮次收尾时回填占位符锚点 */
    private final ChatFileTurnTracker turnTracker;

    /** 会话归属守卫：文件按 fileId 操作时，校验其所属会话属于当前登录用户 */
    private final SessionGuard sessionGuard;

    /**
     * 上传用户源文件：校验类型与数量后，元数据落表、内容以文件 ID 命名落盘
     *
     * @param sessionId 会话 ID（即 memoryId），不允许为空
     * @param files     上传的文件列表，单次最多 5 个
     * @return 落库后的文件元数据列表（含文件 ID，供前端发消息时透传）
     */
    @Override
    public List<ChatFileVO> upload(Long sessionId, List<MultipartFile> files) {
        // 步骤 1：数量校验，空列表与超限直接拒绝
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("请选择要上传的文件");
        }
        if (files.size() > MAX_UPLOAD_COUNT) {
            throw new IllegalStateException("单次最多上传 " + MAX_UPLOAD_COUNT + " 个文件");
        }

        List<ChatFileVO> result = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            // 步骤 2：解析扩展名并做白名单校验，不在 docx/xlsx/pdf 之列（含未知扩展名）一律拒绝
            String originalName = file.getOriginalFilename();
            FileFormat format = FileFormat.ofExtension(extractExtension(originalName));
            if (format == null || !UPLOAD_FORMATS.contains(format)) {
                throw new IllegalStateException("不支持的文件类型：" + originalName);
            }

            // 步骤 3：先插入元数据行拿自增 ID（stored_path 列非空，先占空串，落盘后回填真实路径）
            AiChatFile record = AiChatFile.builder()
                    .sessionId(sessionId)
                    .fileName(originalName)
                    .kind(FileKind.SOURCE.code())
                    .format(format.code())
                    .fileSize(file.getSize())
                    .storedPath("")
                    .build();
            save(record);

            // 步骤 4：以文件 ID 命名落盘，并把相对路径回填到元数据行
            String storedPath = storage.store(sessionId, record.getId(), format, readBytes(file));
            record.setStoredPath(storedPath);
            updateById(record);

            result.add(toVO(record));
        }
        log.info("上传会话文件完成：memoryId={}，成功 {} 个", sessionId, result.size());
        return result;
    }

    /**
     * 查询某会话的全部有效文件（上传 + 生成），按最后修改时间倒序
     *
     * @param sessionId 会话 ID
     * @return 文件元数据列表，供前端占位符卡片渲染
     */
    @Override
    public List<ChatFileVO> listBySession(Long sessionId) {
        return query().eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .orderByDesc(true, "updated_at")
                .list()
                .stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 分页查询会话文件：关键词过滤、排序与分页全部由数据库完成
     *
     * @param sessionId 会话 ID
     * @param keyword   关键词，可为空表示不过滤
     * @param pageNum   页码，从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果对象
     */
    @Override
    public IPage<ChatFileVO> listPage(Long sessionId, String keyword, int pageNum, int pageSize) {
        // 步骤 1：关键词是否有效决定 LIKE 条件是否拼进 SQL，空关键词不拼以免退化成全匹配
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String needle = hasKeyword ? keyword.trim() : null;

        // 步骤 2：过滤、排序、分页一次下推数据库，总条数由分页插件自动补 COUNT
        // 关键词需命中文件名或用途描述之一，用 and(Consumer) 把 or 条件整体括起来，避免污染外层的会话与删除过滤
        IPage<AiChatFile> page = query().eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .and(hasKeyword, w -> w.like(true, "file_name", needle)
                        .or()
                        .like(true, "description", needle))
                .orderByDesc(true, "updated_at")
                .page(new Page<>(pageNum, pageSize));

        // 步骤 3：实体分页结果转对外视图，total/current/pages 元信息原样保留
        return page.convert(this::toVO);
    }

    /**
     * 按 ID 集合批量取文件视图（发消息时解析前端透传的上传文件清单）
     *
     * @param fileIds 文件 ID 集合，允许为空
     * @return 文件视图列表；入参为空时返回空列表
     */
    @Override
    public List<ChatFileVO> listVOByIds(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return listByIds(fileIds).stream().map(this::toVO).toList();
    }

    /**
     * 批量回填占位符锚点：把指定文件的 anchor_message_id 更新为给定消息 ID
     *
     * @param fileIds         文件 ID 集合，允许为空
     * @param anchorMessageId 锚定的消息 ID，为空时不做任何更新
     */
    @Override
    public void anchorFiles(Collection<Long> fileIds, Long anchorMessageId) {
        if (fileIds == null || fileIds.isEmpty() || anchorMessageId == null) {
            return;
        }
        update().in(true, "id", fileIds)
                .set(true, "anchor_message_id", anchorMessageId)
                .update();
    }

    /**
     * 按文件 ID 定位当前会话内的有效文件：文件必须属于该会话且未被删除，否则一律视为不存在
     *
     * @param sessionId 会话 ID，用于校验文件归属
     * @param fileId    文件 ID
     * @return 文件元数据行；不存在、已删除或不属于该会话时返回 null
     */
    @Override
    public AiChatFile findOwned(Long sessionId, Long fileId) {
        // 步骤 1：入参兜底，任一为空都视为查不到
        if (sessionId == null || fileId == null) {
            return null;
        }
        // 步骤 2：按主键取行，再校验删除标记与会话归属，避免跨会话越权访问
        AiChatFile record = getById(fileId);
        if (record == null
                || Integer.valueOf(1).equals(record.getDelFlag())
                || !sessionId.equals(record.getSessionId())) {
            return null;
        }
        return record;
    }

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
    @Override
    public AiChatFile saveGenerated(Long sessionId, Long fileId, String fileName, String html, String description) {
        // 步骤 1：计算内容大小，供元数据展示
        long size = html.getBytes(StandardCharsets.UTF_8).length;

        // 步骤 2：定位目标行——未给 fileId 即新建，给了即复用该行做覆盖式修改
        AiChatFile record;
        if (fileId == null) {
            // stored_path 列非空，先占空串，落盘后回填真实路径
            record = AiChatFile.builder()
                    .sessionId(sessionId)
                    .fileName(fileName)
                    .kind(FileKind.GENERATED.code())
                    .format(FileFormat.HTML.code())
                    .description(description)
                    .fileSize(size)
                    .storedPath("")
                    .build();
            save(record);
        } else {
            // 覆写用户上传的源文件会破坏原始资料，故仅允许覆盖 AI 生成的文件
            record = findOwned(sessionId, fileId);
            if (record == null) {
                throw new IllegalStateException("要修改的文件不存在：" + fileId);
            }
            if (record.getKind() == null || record.getKind() != FileKind.GENERATED.code()) {
                throw new IllegalStateException("该文件是用户上传的源文件，不支持修改：" + record.getFileName());
            }
            record.setFileSize(size);
            // 文件名与描述为空时保留原值，避免覆盖式修改把展示名或用途说明抹掉
            if (fileName != null && !fileName.isBlank()) {
                record.setFileName(fileName);
            }
            if (description != null && !description.isBlank()) {
                record.setDescription(description);
            }
        }

        // 步骤 3：以文件 ID 命名落盘并回填路径（覆盖与新建共用同一路径）
        record.setStoredPath(storage.storeText(sessionId, record.getId(), FileFormat.HTML, html));
        updateById(record);

        // 步骤 4：登记本轮变更，轮次收尾时统一把占位符锚到最后一条消息
        turnTracker.mark(sessionId, record.getId());
        return record;
    }

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
    @Override
    public AiChatFile editGenerated(Long sessionId, Long fileId, String oldString, String newString) {
        // 步骤 1：被替换片段不能为空——空串会在任意位置命中，替换语义随之失效
        if (oldString == null || oldString.isEmpty()) {
            throw new IllegalStateException("要替换的原文片段不能为空");
        }

        // 步骤 2：定位目标文件并校验类型，仅 AI 生成的 HTML 产物可做局部替换
        AiChatFile record = findOwned(sessionId, fileId);
        if (record == null) {
            throw new IllegalStateException("要修改的文件不存在：" + fileId);
        }
        if (record.getKind() == null || record.getKind() != FileKind.GENERATED.code()
                || FileFormat.ofCode(record.getFormat()) != FileFormat.HTML) {
            throw new IllegalStateException("该文件是用户上传的源文件，不支持修改：" + record.getFileName());
        }

        // 步骤 3：读回现有正文并校验命中唯一——零处命中说明片段与原文不符，多处命中则无法确定该改哪一处
        String content = storage.readText(record.getStoredPath());
        int start = content.indexOf(oldString);
        if (start < 0) {
            throw new IllegalStateException("文件中未找到要替换的原文片段，请先用 read_file 核对原文");
        }
        if (content.indexOf(oldString, start + oldString.length()) >= 0) {
            throw new IllegalStateException("要替换的原文片段在文件中出现多次，请补充更长的上下文以唯一定位");
        }

        // 步骤 4：拼出新正文后复用统一的生成流程，落盘、刷新元数据与登记本轮变更一并完成
        String updated = content.substring(0, start)
                + (newString == null ? "" : newString)
                + content.substring(start + oldString.length());
        return saveGenerated(sessionId, fileId, null, updated, null);
    }

    /**
     * 读取生成文件的 HTML 内容：供前端 iframe 预览
     *
     * @param fileId 文件 ID
     * @return HTML 文本
     */
    @Override
    public String previewHtml(Long fileId) {
        return readGeneratedHtml(requireFile(fileId));
    }

    /**
     * 导出生成文件为 PDF：读回 HTML 原文渲染成 PDF 字节，渲染在内存中完成、不落盘
     *
     * @param fileId 文件 ID，须为 AI 生成的 HTML 产物
     * @return 导出结果（对用户可见的下载文件名 + PDF 字节内容）
     */
    @Override
    public FileExportVO exportPdf(Long fileId) {
        // 步骤 1：定位文件并读回 HTML 原文（非 HTML 产物在这一步被拒）
        AiChatFile record = requireFile(fileId);
        String html = readGeneratedHtml(record);

        // 步骤 2：渲染为 PDF 字节，全程在内存中完成、不落盘，磁盘上不会残留导出的 PDF
        byte[] content = pdfRenderService.render(html);

        // 步骤 3：下载名沿用用户可见的业务名，把 .html 后缀换成 .pdf
        String fileName = record.getFileName().replaceAll("(?i)\\.html?$", "") + ".pdf";
        log.info("导出会话文件为 PDF：fileId={}，fileName={}，字节数={}", fileId, fileName, content.length);
        return FileExportVO.builder().fileName(fileName).content(content).build();
    }

    /**
     * 读取生成产物的 HTML 原文：预览与导出共用同一份磁盘内容，
     * 非 HTML 格式（上传的 docx/xlsx/pdf）一律拒绝
     *
     * @param record 文件元数据行
     * @return HTML 文本
     */
    private String readGeneratedHtml(AiChatFile record) {
        // 仅 HTML 产物支持预览与导出（上传的 docx/xlsx/pdf 暂不处理）
        if (FileFormat.ofCode(record.getFormat()) != FileFormat.HTML) {
            throw new IllegalStateException("该文件不是可预览或导出的文档产物");
        }
        return storage.readText(record.getStoredPath());
    }

    /**
     * 按 ID 定位有效文件：文件存在、未删除，且其所属会话属于当前登录用户，
     * 任一不满足都抛错，防止跨用户越权预览/导出
     *
     * @param fileId 文件 ID
     * @return 文件元数据行
     */
    private AiChatFile requireFile(Long fileId) {
        // 步骤 1：按主键取行并校验删除标记
        AiChatFile record = getById(fileId);
        if (record == null || Integer.valueOf(1).equals(record.getDelFlag())) {
            throw new IllegalStateException("文件不存在");
        }

        // 步骤 2：校验文件所属会话归属当前登录用户，越权访问与不存在同样处理
        sessionGuard.requireOwned(record.getSessionId());
        return record;
    }

    /**
     * 从文件名中截取扩展名（不含点，忽略大小写由调用方处理）
     *
     * @param fileName 文件名（含扩展名），允许为空
     * @return 扩展名；无扩展名时返回 null
     */
    private String extractExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot + 1);
    }

    /**
     * 读取上传文件的字节内容
     *
     * @param file 上传的文件
     * @return 文件字节内容
     */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + file.getOriginalFilename(), e);
        }
    }

    /**
     * 实体转对外视图：剥离磁盘路径等内部字段
     *
     * @param record 文件元数据行
     * @return 对外视图对象
     */
    private ChatFileVO toVO(AiChatFile record) {
        return ChatFileVO.builder()
                .id(record.getId())
                .fileName(record.getFileName())
                .kind(record.getKind())
                .format(record.getFormat())
                .description(record.getDescription())
                .fileSize(record.getFileSize())
                .anchorMessageId(record.getAnchorMessageId())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    /**
     * 清理会话的文件数据：逻辑删除该会话全部文件元数据，并移除磁盘上对应的会话目录。
     * 供删除会话时一并调用，避免残留无主文件
     *
     * @param sessionId 会话 ID，不允许为空
     */
    @Override
    public void deleteBySession(Long sessionId) {
        // 步骤 1：入参兜底，会话 ID 为空时无从删除
        if (sessionId == null) {
            return;
        }

        // 步骤 2：逻辑删除该会话的文件元数据行（改删除标记，不删物理行）
        update().eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .set(true, "del_flag", 1)
                .update();

        // 步骤 3：删除磁盘上的会话目录；失败只记日志，不影响会话本身的删除结果
        boolean deleted = storage.deleteSessionDir(sessionId);
        if (!deleted) {
            log.warn("删除会话文件目录失败：sessionId={}", sessionId);
        } else {
            log.info("已清理会话文件：sessionId={}", sessionId);
        }
    }
}
