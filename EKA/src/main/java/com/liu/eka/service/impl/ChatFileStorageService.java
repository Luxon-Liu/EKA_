package com.liu.eka.service.impl;

import com.liu.eka.common.UserContext;
import com.liu.eka.entity.conversation.FileFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 会话文件磁盘存储服务：负责「{用户ID}/{会话ID}/{文件ID}.{扩展名}」目录结构下的
 * 路径计算、落盘与读取，是上传接口与文件读写工具共用的底层存储入口
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Service
public class ChatFileStorageService {

    /** 会话文件存储根目录（相对 EKA 运行目录），目录结构 {userId}/{sessionId}/{fileId}.{ext} */
    @Value("${file.session-root}")
    private String fileRoot;

    /**
     * 计算文件在磁盘上的相对路径（相对 file.session-root）
     *
     * @param sessionId 会话 ID，不允许为空
     * @param fileId    文件 ID（表主键），不允许为空
     * @param format    文件格式，决定扩展名
     * @return 形如 "{用户ID}/5/12.docx" 的相对路径（统一用正斜杠，跨平台一致）
     */
    public String buildRelativePath(Long sessionId, Long fileId, FileFormat format) {
        return UserContext.requireUserId() + "/" + sessionId + "/" + fileId + "." + format.extension();
    }

    /**
     * 把字节内容落盘为「{fileId}.{扩展名}」，所属目录不存在时自动创建
     *
     * @param sessionId 会话 ID，决定所属目录
     * @param fileId    文件 ID，作为磁盘文件名
     * @param format    文件格式，决定扩展名
     * @param bytes     文件字节内容
     * @return 落盘后的相对路径（相对 file.session-root）
     */
    public String store(Long sessionId, Long fileId, FileFormat format, byte[] bytes) {
        // 步骤 1：按命名规则拼出目标路径
        String relativePath = buildRelativePath(sessionId, fileId, format);
        Path target = resolve(relativePath);
        try {
            // 步骤 2：确保所属会话目录存在（首次上传时自动创建）
            Files.createDirectories(target.getParent());
            // 步骤 3：写入内容，同名直接覆盖（写工具/edit 工具复用同一路径）
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("文件落盘失败：" + relativePath, e);
        }
        return relativePath;
    }

    /**
     * 把文本内容以 UTF-8 落盘（生成 HTML 产物时使用）
     *
     * @param sessionId 会话 ID，决定所属目录
     * @param fileId    文件 ID，作为磁盘文件名
     * @param format    文件格式，决定扩展名
     * @param text      文本内容
     * @return 落盘后的相对路径（相对 file.session-root）
     */
    public String storeText(Long sessionId, Long fileId, FileFormat format, String text) {
        return store(sessionId, fileId, format, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把相对路径解析为磁盘绝对路径
     *
     * @param relativePath 相对 file.session-root 的路径，允许为空
     * @return 磁盘绝对路径
     */
    public Path resolve(String relativePath) {
        return Path.of(fileRoot, relativePath);
    }

    /**
     * 读取文件原始字节（文档解析等场景使用）
     *
     * @param relativePath 相对 file.session-root 的路径
     * @return 文件字节内容
     */
    public byte[] readBytes(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("文件读取失败：" + relativePath, e);
        }
    }

    /**
     * 以 UTF-8 读取文本文件（预览生成的 HTML 时使用）
     *
     * @param relativePath 相对 file.session-root 的路径
     * @return 文本内容
     */
    public String readText(String relativePath) {
        return new String(readBytes(relativePath), StandardCharsets.UTF_8);
    }

    /**
     * 删除某个会话的整个文件目录：目录下所有上传源文件与生成产物一并移除
     *
     * <p>目录不存在时静默跳过（会话从未上传或生成过文件属正常情况）；
     * 删除失败不向上抛异常，由调用方决定是否记录，避免影响会话本身的删除结果</p>
     *
     * @param sessionId 会话 ID，不允许为空
     * @return true 表示目录已删除或本就不存在；false 表示删除过程中出错
     */
    public boolean deleteSessionDir(Long sessionId) {
        // 步骤 1：入参兜底，会话 ID 为空时无从定位目录
        if (sessionId == null) {
            return false;
        }

        // 步骤 2：解析会话目录并判断是否存在，不存在即视为已删除
        Path sessionDir = resolve(UserContext.requireUserId() + "/" + sessionId);
        if (!Files.exists(sessionDir)) {
            return true;
        }

        try {
            // 步骤 3：递归删除目录（先删文件再删目录），保证目录下所有产物都被清掉
            Stream<Path> paths = Files.walk(sessionDir);
            try {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("删除会话文件失败：" + path, e);
                    }
                });
            } finally {
                paths.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
