package com.liu.eka.controller;

import com.liu.eka.common.Result;
import com.liu.eka.common.SessionGuard;
import com.liu.eka.entity.conversation.ChatFileVO;
import com.liu.eka.entity.conversation.FileExportVO;
import com.liu.eka.service.ChatFileService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 会话文件入口：上传用户源文件、查询占位符列表、预览生成的 HTML 产物、导出产物为 PDF
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Slf4j
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
@Validated
public class ChatFileController {

    /** 会话文件业务服务 */
    private final ChatFileService chatFileService;

    /** 会话归属守卫：上传/查询前先确认会话属于当前登录用户 */
    private final SessionGuard sessionGuard;

    /**
     * 上传用户源文件：单次最多 5 个，仅支持 docx/xlsx/pdf
     *
     * @param memoryId 记忆 ID（即会话 ID），注解校验非空
     * @param files    上传的文件，前端已限制数量与类型，后端再校验一次
     * @return 落库后的文件元数据列表
     */
    @PostMapping("/upload")
    public Result<List<ChatFileVO>> upload(
            @NotNull(message = "记忆 ID 不能为空") @RequestParam Long memoryId,
            @RequestParam("files") List<MultipartFile> files) {
        log.info("上传会话文件：memoryId={}，文件数={}", memoryId, files == null ? 0 : files.size());
        // 先校验会话归属，防止向他人会话上传文件
        sessionGuard.requireOwned(memoryId);
        return Result.ok(chatFileService.upload(memoryId, files));
    }

    /**
     * 查询会话文件列表：占位符接口，前端按 anchorMessageId 把卡片挂到对应消息下
     *
     * @param memoryId 记忆 ID（即会话 ID），注解校验非空
     * @return 该会话的全部有效文件（上传 + 生成）
     */
    @GetMapping("/list")
    public Result<List<ChatFileVO>> list(
            @NotNull(message = "记忆 ID 不能为空") @RequestParam Long memoryId) {
        log.info("查询会话文件列表：memoryId={}", memoryId);
        // 先校验会话归属，防止读取他人会话的文件清单
        sessionGuard.requireOwned(memoryId);
        return Result.ok(chatFileService.listBySession(memoryId));
    }

    /**
     * 预览生成文件的 HTML：前端 iframe 直接加载本接口地址
     *
     * @param fileId 文件 ID，注解校验非空
     * @return HTML 内容（text/html;charset=UTF-8）
     */
    @GetMapping("/preview")
    public ResponseEntity<byte[]> preview(
            @NotNull(message = "文件 ID 不能为空") @RequestParam Long fileId) {
        log.info("预览会话文件：fileId={}", fileId);
        byte[] html = chatFileService.previewHtml(fileId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html);
    }

    /**
     * 导出生成文件为 PDF：后端把 HTML 渲染成 PDF 后以字节流返回，渲染在内存中完成、不落盘
     *
     * @param fileId 文件 ID，注解校验非空
     * @return PDF 字节流，Content-Disposition 带对用户可见的业务文件名
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @NotNull(message = "文件 ID 不能为空") @RequestParam Long fileId) {
        log.info("导出会话文件为 PDF：fileId={}", fileId);
        FileExportVO exported = chatFileService.exportPdf(fileId);
        // 文件名含中文，须按 RFC 5987 编码后再放进 Content-Disposition，否则浏览器解析出乱码；
        // URLEncoder 会把空格编成「+」，需还原成「%20」
        String encodedName = URLEncoder.encode(exported.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(exported.getContent());
    }

}
