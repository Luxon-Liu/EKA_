// EKA 会话级文件面板：右侧抽屉列出本会话全部文件（上传 + 生成），
// 作为消息流卡片的兜底入口——文件多、或卡片被上下文挤走时用这里找
"use client";

import type { ChatFileVO } from "@/lib/eka-api";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { FileCard } from "./file-cards";

/**
 * 会话文件抽屉：按最后修改时间倒序展示全量文件，点击可预览/导出
 */
export function SessionFilesSheet({
  open,
  onOpenChange,
  files,
  onPreview,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  files: ChatFileVO[];
  onPreview: (file: ChatFileVO) => void;
}) {
  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
    >
      <SheetContent
        side="right"
        className="w-full gap-0 p-0 sm:max-w-md"
      >
        <SheetHeader className="border-b p-4">
          <SheetTitle className="text-base">会话文件（{files.length}）</SheetTitle>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto p-4">
          {files.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              本会话还没有文件。上传 docx/xlsx/pdf，或让智能体生成报告后，会出现在这里。
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {files.map((file) => (
                <FileCard
                  key={file.id}
                  file={file}
                  onPreview={onPreview}
                  className="w-full"
                />
              ))}
            </div>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
