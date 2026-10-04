package com.liu.eka.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * HTML 转 PDF 渲染服务：把 Agent 生成的 HTML 产物渲染成 PDF 字节流回传前端，渲染全程不落盘
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Slf4j
@Service
public class PdfRenderService {

    /** 中文字体族名：注入样式统一引用它，避免模型内联的字体名在本机不存在导致整篇丢字 */
    private static final String CJK_FONT_FAMILY = "EkaCjk";

    /**
     * 导出样式覆盖：模型生成的 HTML 常带「网页外壳」（整页灰底、居中白卡片、阴影），
     * 直接渲染会像「纸里套了一张纸」。这里把它中和成纯文档，页面留白交给纸张自身的页边距。
     * 注意渲染器只实现 CSS 2.1 子集，box-shadow 这类属性会被直接忽略而非报错
     */
    private static final String EXPORT_CSS = """
            @page { size: A4; margin: 14mm 12mm; }
            html, body { background: #fff !important; margin: 0 !important; padding: 0 !important; }
            * { font-family: 'EkaCjk' !important; }
            body > * {
              max-width: none !important;
              width: auto !important;
              margin-left: 0 !important;
              margin-right: 0 !important;
              padding-left: 0 !important;
              padding-right: 0 !important;
              box-shadow: none !important;
              background: transparent !important;
            }
            """;

    /** 中文字体文件路径：渲染器内置字体不含中文，必须显式嵌入本机字体，否则中文整篇丢失 */
    @Value("${file.pdf.font-path}")
    private String fontPath;

    /**
     * 把 HTML 渲染为 PDF 字节流
     *
     * @param html 生成产物的 HTML 原文
     * @return PDF 文件的字节内容
     */
    public byte[] render(String html) {
        // 步骤 1：字体缺失时直接失败。静默渲染会得到整篇没有中文的文档，不如尽早暴露问题
        File fontFile = new File(fontPath);
        if (!fontFile.isFile()) {
            throw new IllegalStateException("导出 PDF 失败：找不到中文字体文件 " + fontPath);
        }

        // 步骤 2：注入导出样式并规整为合法 XHTML，渲染器要求输入是格式良好的 XML
        String prepared = prepareHtml(html);

        // 步骤 3：渲染到内存字节流，渲染器全程不碰磁盘，磁盘上不残留导出的 PDF
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFont(fontFile, CJK_FONT_FAMILY);
            builder.withHtmlContent(prepared, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出 PDF 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 规整待渲染的 HTML：统一按 UTF-8 解析输出、补齐 html/head/body 结构、注入导出样式，
     * 并以 XML 语法（自闭合标签）输出，满足渲染器的输入要求
     *
     * @param html 模型生成的原始 HTML，可为空
     * @return 可直接交给渲染器的 XHTML 文本
     */
    private String prepareHtml(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        document.outputSettings().charset(StandardCharsets.UTF_8)
                .syntax(Document.OutputSettings.Syntax.xml);
        document.head().appendElement("style").text(EXPORT_CSS);
        return document.html();
    }
}
