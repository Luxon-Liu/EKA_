package com.liu.eka.entity.conversation;

import lombok.Builder;
import lombok.Data;

/**
 * 文件导出结果：后端渲染 PDF 后一次性把「下载文件名 + PDF 字节」交给控制层，
 * 避免控制层为了取名再查一次库；本对象只在服务端内部传递，不参与 JSON 序列化
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Data
@Builder
public class FileExportVO {

    /** 下载文件名（对用户可见，生成产物的 .html 后缀已换成 .pdf） */
    private String fileName;

    /** PDF 文件的字节内容 */
    private byte[] content;
}
