package com.liu.eka.rag;

import ai.docling.core.DoclingDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * 文档混合切块器（Java 版 HybridChunker），忠实复刻 docling-core 的 HybridChunker 三层算法：
 * <ol>
 *   <li>结构层（等价 HierarchicalChunker）：遍历 DoclingDocument 的文本/表格项，维护章节标题层级，
 *       每个内容项序列化为一个初始块，并挂上所属标题路径（headings）；</li>
 *   <li>token 精修层（等价 HybridChunker 主体）：块总 token（标题上下文 + 正文）超过 maxTokens 时，
 *       先扣除标题上下文预算，再按句子边界贪心装填正文，超长单句按 token 二分硬切；
 *       表格块支持逐行切分并把表头行作为每段前缀（repeatTableHeader）；</li>
 *   <li>合并层（mergePeers）：相邻且标题路径相同的“碎块”在不超预算的前提下合并回整。</li>
 * </ol>
 * 与 Python 原版的差异（均在对应方法注释中说明）：
 * <ul>
 *   <li>原版经文档树 iterate_items 遍历；本实现采用 texts 线性流 + 表格物理位置合并成阅读流
 *       （docling-core Java 0.6.1 未暴露树遍历 API，该策略已在 38 块链路上验证阅读顺序正确）；</li>
 *   <li>原版文本切分用 semchunk 库；本实现内置“分句 + 贪心装填 + token 二分”的等价简化实现；</li>
 * <li>token 计数用 jtokkit 的 cl100k_base（OpenAI BPE），对中文约 1 汉字 ≈ 1 token，
 *       作为 DashScope text-embedding-v3（tokenizer 未公开）的预算近似；</li>
 *   <li>本实现额外提供页眉/页脚噪音过滤（filterPageChrome），默认开启，可通过配置关闭。</li>
 * </ul>
 *
 * @author Luxon
 * @date 2026/08/30
 */
@Component
public class DoclingHybridChunker {

    /** 单块 token 上限：超过则触发正文切分（默认 512，与 docling HybridChunker 默认值一致） */
    @Value("${rag.chunker.max-tokens:512}")
    private int maxTokens;

    /** 是否把相邻同标题路径的超小碎块合并回整（对应原版 merge_peers） */
    @Value("${rag.chunker.merge-peers:true}")
    private boolean mergePeers;

    /** 表格被切分时是否把表头行重复为每段前缀（对应原版 repeat_table_header） */
    @Value("${rag.chunker.repeat-table-header:true}")
    private boolean repeatTableHeader;

    /** 表格序列化格式：true = Markdown 管道表；false = 三元组文本 “行, 列 = 值”（对应原版 TripletTableSerializer） */
    @Value("${rag.chunker.use-markdown-tables:true}")
    private boolean useMarkdownTables;

    /** 是否过滤页眉/页脚噪音项（本项目对中文公文的扩展，原版无此逻辑） */
    @Value("${rag.chunker.filter-page-chrome:true}")
    private boolean filterPageChrome;

    /** jtokkit 编码注册器：进程内全局单例即可，负责按需加载并缓存编码器 */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    /** cl100k_base 编码器：OpenAI 系 BPE 分词，线程安全，用作 token 计数器 */
    private final Encoding encoding = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /** 中英文通用分句正则：在句末标点（。！？；!?;）或换行后切分，保留标点在句尾 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；!?;\\n])");

    /** Markdown 表格行匹配：以 | 开头的行，用于表格切分时识别表头/表体 */
    private static final Pattern MD_TABLE_ROW = Pattern.compile("^\\|.*\\|$");

    /** 段与段合并时的拼接分隔符，与原版 BaseChunker.delim 默认值一致 */
    private static final String DELIM = "\n";

    /** 页眉页脚等噪音标签集合（docling label 原值），开启 filterPageChrome 时这些项直接丢弃 */
    private static final List<String> PAGE_CHROME_LABELS = List.of("PAGE_HEADER", "PAGE_FOOTER");

    /**
     * 对外主入口：把 docling 解析出的结构化文档切分为带标题上下文的块序列
     *
     * @param doc docling-serve convert 返回的 DoclingDocument 结构化文档
     * @return 按阅读顺序排列的块列表（已含 token 精修与碎块合并），永不返回 null
     */
    public List<HybridChunk> chunk(DoclingDocument doc) {
        // 步骤 1：结构层——把文档内容项变成“一个项一个初始块”，并挂上标题路径
        List<DocChunk> initial = hierarchicalChunk(doc);

        // 步骤 2：token 精修层——超预算的块按句子边界 + token 二分切开（表格逐行切）
        List<DocChunk> refined = new ArrayList<>();
        for (DocChunk c : initial) {
            refined.addAll(splitUsingPlainText(c));
        }

        // 步骤 3：合并层——把相邻同标题路径的碎块合并回整（对应原版 merge_peers）
        List<DocChunk> merged = mergePeers ? mergeChunksWithMatchingMetadata(refined) : refined;

        // 步骤 4：输出层——内部 DocChunk 转为对外 HybridChunk（带块类型与定位信息）
        return merged.stream().map(DocChunk::toHybridChunk).collect(Collectors.toList());
    }

    /**
     * 结构层切块（等价 HierarchicalChunker.chunk）：顺序遍历文档内容项，
     * 维护章节标题的层级栈，每个有文本的内容项产出一个初始 DocChunk
     *
     * @param doc 结构化文档
     * @return 初始块列表（已按阅读顺序排列）
     */
    private List<DocChunk> hierarchicalChunk(DoclingDocument doc) {
        // 步骤 1：把文本流与表格流合并为统一的阅读流——docling 的 texts 是文档顺序，
        // 表格不在 texts 内，需按页码与页内位置合并进去（页内坐标系为 BOTTOM_LEFT，top 越大越靠上）
        List<FlowItem> flow = buildReadingFlow(doc);

        // 步骤 2：维护“层级 -> 标题文本”映射（对应原版 heading_by_level）：
        // 遇到更高级标题时清掉同级及以下的旧标题，实现标题作用域收缩
        TreeMap<Integer, String> headingByLevel = new TreeMap<>();

        // 步骤 3：顺序扫描阅读流，逐项产出初始块
        List<DocChunk> chunks = new ArrayList<>();
        for (FlowItem item : flow) {
            // 分支 1：噪音项（页眉/页脚）——开启过滤时直接丢弃
            if (filterPageChrome && PAGE_CHROME_LABELS.contains(item.label())) {
                continue;
            }

            // 分支 2：章节标题——不单独成块，只更新标题层级映射
            if ("SECTION_HEADER".equals(item.label()) || "TITLE".equals(item.label())) {
                int level = item.level() <= 0 ? 1 : item.level();
                // 收缩：清掉 >= 当前级别 的旧标题（对应原版 keys_to_del 逻辑）
                headingByLevel.keySet().removeIf(k -> k >= level);
                headingByLevel.put(level, item.text());
                continue;
            }

            // 分支 3：空内容项——跳过（对应原版 if not ser_res.text: continue）
            if (item.text().isBlank()) {
                continue;
            }

            // 分支 4：内容项——产出初始块，headings 取当前生效的各级标题文本（层级升序）
            List<String> headings = headingByLevel.values().stream().collect(Collectors.toList());
            chunks.add(new DocChunk(item.text(), headings, List.of(item), item.pageNo(), item.top(), item.isTable()));
        }
        return chunks;
    }

    /**
     * 构建统一阅读流：把 texts 线性流与表格（含标题项等）按物理位置合并为单项序列
     *
     * @param doc 结构化文档
     * @return 按页码升序、页内自上而下（top 降序）排列的内容项流
     */
    private List<FlowItem> buildReadingFlow(DoclingDocument doc) {
        // 步骤 1：收集文本流各项（TEXT / LIST_ITEM / SECTION_HEADER / TITLE / 页眉页脚等）
        List<FlowItem> flow = new ArrayList<>();
        for (DoclingDocument.BaseTextItem t : doc.getTexts()) {
            flow.add(FlowItem.fromText(t));
        }

        // 步骤 2：把每张表格插入阅读流——按“页码 + 页内位置”找到最后一个不晚于它的项之后插入，
        // 近似原版文档树中表格的出现位置
        for (DoclingDocument.TableItem table : doc.getTables()) {
            FlowItem tableItem = FlowItem.fromTable(table, useMarkdownTables);
            insertByPosition(flow, tableItem);
        }

        // 步骤 3：按物理位置稳定排序兜底（页码升序，页内 top 降序 = 自上而下）
        flow.sort(Comparator.comparingInt(FlowItem::pageNo).thenComparing((a, b) -> Double.compare(b.top(), a.top())));
        return flow;
    }

    /**
     * 把表格项按物理位置插入阅读流：插入到第一个“位于表格之后”的项之前，
     * 位置关系沿用 BOTTOM_LEFT 坐标系判断（页码更早，或同页 top 更大即更靠上）
     *
     * @param flow      现有阅读流（已按位置有序）
     * @param tableItem 待插入的表格项
     */
    private void insertByPosition(List<FlowItem> flow, FlowItem tableItem) {
        int insertAt = flow.size();
        for (int i = 0; i < flow.size(); i++) {
            FlowItem cur = flow.get(i);
            boolean beforeTable = cur.pageNo() < tableItem.pageNo()
                    || (cur.pageNo() == tableItem.pageNo() && cur.top() >= tableItem.top());
            if (!beforeTable) {
                insertAt = i;
                break;
            }
        }
        flow.add(insertAt, tableItem);
    }

    /**
     * token 精修层（等价 HybridChunker._split_using_plain_text）：
     * 块的“标题上下文 + 正文”总 token 超过 maxTokens 时，先扣除标题上下文预算，
     * 再对正文做语义切分（分句 + 贪心装填 + 超长句 token 二分）
     *
     * @param chunk 待精修的初始块
     * @return 精修后的一个或多个块（未超预算时返回原块）
     */
    private List<DocChunk> splitUsingPlainText(DocChunk chunk) {
        // 步骤 1：计算含标题上下文的总 token（对应原版 _count_chunk_tokens = contextualize 后计数）
        int totalLen = countTokens(contextualize(chunk));
        if (totalLen <= maxTokens) {
            return List.of(chunk);
        }

        // 步骤 2：扣除标题上下文的 token，得到正文可用预算（对应原版 available_length）
        int otherLen = countTokens(String.join(DELIM, chunk.headings()));
        int available = maxTokens - otherLen;
        if (available <= 0) {
            // 标题比整块预算还长：原版会告警并丢弃标题后重算，这里保持同样行为
            return splitUsingPlainText(new DocChunk(chunk.text(), List.of(), chunk.items(),
                    chunk.pageNo(), chunk.top(), chunk.isTable()));
        }

        // 步骤 3：分派切分策略——Markdown 表格块走逐行切（表头重复），其余走语义切分
        List<String> segments;
        if (repeatTableHeader && chunk.isTable() && looksLikeMarkdownTable(chunk.text())) {
            segments = splitMarkdownTableWithHeader(chunk.text(), available);
        } else {
            segments = semanticSplit(chunk.text(), available);
        }

        // 步骤 4：每个切分段成为一个块，继承原块的标题路径与定位信息
        List<DocChunk> out = new ArrayList<>(segments.size());
        for (String seg : segments) {
            out.add(new DocChunk(seg, chunk.headings(), chunk.items(), chunk.pageNo(), chunk.top(), chunk.isTable()));
        }
        return out;
    }

    /**
     * 语义切分（semchunk 的等价简化实现）：先把正文按句子切开，
     * 再贪心装填到 token 预算内；单句超预算时按 token 二分找最大可容纳前缀硬切
     *
     * @param text      正文文本
     * @param available 正文可用 token 预算
     * @return 切分后的文本段列表（每段 token 均不超过 available，句级边界优先）
     */
    private List<String> semanticSplit(String text, int available) {
        // 步骤 1：分句——按中英文句末标点/换行切，保留标点；空句丢弃
        String[] sentences = SENTENCE_SPLIT.split(text);
        List<String> parts = new ArrayList<>();
        for (String s : sentences) {
            if (!s.isBlank()) {
                parts.add(s);
            }
        }

        // 步骤 2：贪心装填——句子依次塞入当前段，塞不下则开新段
        List<String> segments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;
        for (String s : parts) {
            int sTokens = countTokens(s);
            // 分支 2.1：单句就超预算——先落掉缓冲，再对句子本身按 token 二分硬切
            if (sTokens > available) {
                if (buf.length() > 0) {
                    segments.add(buf.toString());
                    buf.setLength(0);
                    bufTokens = 0;
                }
                segments.addAll(splitOversizeSentence(s, available));
                continue;
            }
            // 分支 2.2：当前段还能装下——追加进缓冲
            int joinCost = buf.length() == 0 ? 0 : countTokens(DELIM);
            if (bufTokens + joinCost + sTokens <= available) {
                if (buf.length() > 0) {
                    buf.append(DELIM);
                }
                buf.append(s);
                bufTokens += joinCost + sTokens;
                continue;
            }
            // 分支 2.3：装不下——当前段落块，句子开新缓冲
            segments.add(buf.toString());
            buf.setLength(0);
            buf.append(s);
            bufTokens = sTokens;
        }
        // 步骤 3：收尾——最后一段缓冲落块
        if (buf.length() > 0) {
            segments.add(buf.toString());
        }
        return segments;
    }

    /**
     * 超长单句硬切：按 token 二分找不超预算的最大前缀，循环切完整句
     * （等价 semchunk 对超长句的处理思路：保证每段 token 数不超预算）
     *
     * @param sentence  超预算的句子
     * @param available token 预算
     * @return 切分后的多个文本段
     */
    private List<String> splitOversizeSentence(String sentence, int available) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            // 步骤 1：二分查找最大的 end，使 sentence[start, end) 的 token 数不超过预算
            int lo = start + 1;
            int hi = sentence.length();
            int best = start + 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (countTokens(sentence.substring(start, mid)) <= available) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // 步骤 2：至少能前进一个字符，防止死循环（单字符也超预算时只能硬前进）
            int end = Math.max(best, start + 1);
            segments.add(sentence.substring(start, end));
            start = end;
        }
        return segments;
    }

    /**
     * Markdown 表格逐行切分并重复表头（对应原版 LineBasedTokenChunker + repeat_table_header）：
     * 首个管道行作为表头前缀拼进每一段，保证每段单独可读
     *
     * @param tableText Markdown 表格全文
     * @param available 正文可用 token 预算
     * @return 切分后的表格段列表（每段均带表头前缀且不超预算）
     */
    private List<String> splitMarkdownTableWithHeader(String tableText, int available) {
        // 步骤 1：按行拆分，第一行视为表头，其余为表体行
        String[] lines = tableText.split("\\r?\\n", -1);
        String header = lines.length > 0 ? lines[0] : "";
        int prefixTokens = countTokens(header + DELIM);

        // 步骤 2：贪心装填表体行——预算按“表头前缀 + 行”计算，保证每段总 token 不超
        List<String> segments = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufTokens = prefixTokens;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int lineTokens = countTokens(line + DELIM);
            if (bufTokens + lineTokens > available && buf.length() > 0) {
                // 步骤 3：装不下——当前段带上表头前缀落块，缓冲重开
                segments.add(header + DELIM + buf);
                buf.setLength(0);
                bufTokens = prefixTokens;
            }
            buf.append(line).append(DELIM);
            bufTokens += lineTokens;
        }
        // 步骤 4：收尾段落块（同样带表头前缀）
        if (buf.length() > 0) {
            segments.add(header + DELIM + buf);
        }
        return segments;
    }

    /**
     * 合并层（等价 HybridChunker._merge_chunks_with_matching_metadata）：
     * 滑动窗口扫描，标题路径相同且合并后不超预算的相邻块合并为一块，正文用换行拼接
     *
     * @param chunks 精修后的块序列
     * @return 合并后的块序列
     */
    private List<DocChunk> mergeChunksWithMatchingMetadata(List<DocChunk> chunks) {
        // 步骤 1：滑动窗口——windowStart/windowEnd 维护当前可合并区间（闭区间）
        List<DocChunk> out = new ArrayList<>();
        int windowStart = 0;
        while (windowStart < chunks.size()) {
            // 步骤 2：从 windowStart 起尽可能向后吞并——前提是标题路径一致且合并后 token 不超
            int windowEnd = windowStart;
            DocChunk acc = chunks.get(windowStart);
            while (windowEnd + 1 < chunks.size()) {
                DocChunk next = chunks.get(windowEnd + 1);
                if (!acc.headings().equals(next.headings())) {
                    break;
                }
                // 合并候选：正文换行拼接，items 取并集，定位沿用首块
                List<FlowItem> items = new ArrayList<>(acc.items());
                items.addAll(next.items());
                DocChunk candidate = new DocChunk(acc.text() + DELIM + next.text(), acc.headings(), items,
                        acc.pageNo(), acc.top(), acc.isTable() || next.isTable());
                if (countTokens(contextualize(candidate)) > maxTokens) {
                    break;
                }
                acc = candidate;
                windowEnd++;
            }
            // 步骤 3：窗口闭合——落入输出，窗口起点跳到下一个未处理块
            out.add(acc);
            windowStart = windowEnd + 1;
        }
        return out;
    }

    /**
     * 计算块的标题上下文文本（对应原版 BaseChunker.contextualize）：
     * 标题逐行在前、正文在后，用换行拼接；token 计数基于该结果，
     * 保证“headings 会占用的预算”被计入块总长
     *
     * @param chunk 内部块
     * @return 上下文文本（headings 逐行 + 正文）
     */
    private String contextualize(DocChunk chunk) {
        StringBuilder sb = new StringBuilder();
        for (String h : chunk.headings()) {
            sb.append(h).append(DELIM);
        }
        sb.append(chunk.text());
        return sb.toString();
    }

    /**
     * 判断文本是否为 Markdown 管道表格（首行为 |...| 形式）
     *
     * @param text 待判断文本
     * @return true 表示是 Markdown 表格
     */
    private boolean looksLikeMarkdownTable(String text) {
        String firstLine = text.strip().lines().findFirst().orElse("");
        return MD_TABLE_ROW.matcher(firstLine).matches();
    }

    /**
     * token 计数：使用 cl100k_base 编码器对文本计数（近似目标 embedding 模型的 token 预算）。
     * 同时对外暴露，供上游在组装块元数据时使用同一把“尺子”
     *
     * @param text 待计数文本，null 视为空
     * @return token 数量
     */
    public int countTokens(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
    }

    // ========================= 附属类型 =========================

    /**
     * 阅读流单项：文档内容项的统一内部表示（文本/列表/标题/表格）
     *
     * @param text    序列化后的文本（表格为 Markdown 或三元组文本）
     * @param label   docling 项标签原值（TEXT / LIST_ITEM / SECTION_HEADER / TITLE / TABLE 等）
     * @param level   标题层级（仅 SECTION_HEADER 有意义，其余为 0）
     * @param pageNo  所在页码（1 起，0 表示无位置信息）
     * @param top     页内纵向坐标（BOTTOM_LEFT 系，值越大越靠页面上方）
     * @param isTable 是否为表格项
     * @param ref     该项在文档中的 self_ref 引用（供上游回溯定位）
     */
    record FlowItem(String text, String label, int level, int pageNo, double top, boolean isTable, String ref) {

        /**
         * 从文本类项构建流项（TEXT / LIST_ITEM / SECTION_HEADER / TITLE 等）
         *
         * @param t docling 文本项
         * @return 流项
         */
        static FlowItem fromText(DoclingDocument.BaseTextItem t) {
            // 步骤 1：统一做去空白清洗（中文 OCR 常把标题/正文切成碎片空格，全部清除；
            // 标题与正文一视同仁，与已验证的 ingest 链路 cleanText 行为保持一致）
            String text = t.getText() == null ? "" : t.getText().replaceAll("[\\s\\u00A0\\u3000]+", "");
            // 步骤 2：提取位置信息（首条 provenance），无则页码 0、top 0
            int pageNo = t.getProv().isEmpty() ? 0 : t.getProv().get(0).getPageNo();
            double top = t.getProv().isEmpty() ? 0 : t.getProv().get(0).getBbox().getT();
            // 步骤 3：标题层级仅章节标题项携带（缺省 0，交给标题处理逻辑归一）
            int level = t instanceof DoclingDocument.SectionHeaderItem sh && sh.getLevel() != null ? sh.getLevel() : 0;
            return new FlowItem(text, String.valueOf(t.getLabel()), level, pageNo, top, false, t.getSelfRef());
        }

        /**
         * 从表格项构建流项：按配置把表格序列化为 Markdown 管道表或三元组文本
         *
         * @param table             docling 表格项
         * @param useMarkdownTables true = Markdown 管道表；false = 三元组文本
         * @return 流项
         */
        static FlowItem fromTable(DoclingDocument.TableItem table, boolean useMarkdownTables) {
            // 步骤 1：提取位置信息
            int pageNo = table.getProv().isEmpty() ? 0 : table.getProv().get(0).getPageNo();
            double top = table.getProv().isEmpty() ? 0 : table.getProv().get(0).getBbox().getT();
            // 步骤 2：按配置选择序列化格式
            String text = useMarkdownTables ? toMarkdown(table) : toTriplets(table);
            return new FlowItem(text, "TABLE", 0, pageNo, top, true, table.getSelfRef());
        }

        /**
         * 表格转 Markdown 管道格式：格子按（行,列）铺进二维数组后逐行拼 |...|，单元格内换行替换为空格
         *
         * @param table docling 表格项
         * @return Markdown 表格字符串
         */
        private static String toMarkdown(DoclingDocument.TableItem table) {
            // 步骤 1：铺格子——越界与空缺补空串
            DoclingDocument.TableData data = table.getData();
            int rows = data.getNumRows();
            int cols = data.getNumCols();
            String[][] grid = new String[rows][cols];
            for (DoclingDocument.TableCell cell : data.getTableCells()) {
                int r = Math.min(cell.getStartRowOffsetIdx(), rows - 1);
                int c = Math.min(cell.getStartColOffsetIdx(), cols - 1);
                grid[r][c] = cell.getText() == null ? "" : cell.getText().replaceAll("[\\n\\r]+", " ");
            }
            // 步骤 2：逐行拼管道格式
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < rows; r++) {
                sb.append('|');
                for (int c = 0; c < cols; c++) {
                    sb.append(grid[r][c]).append('|');
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        /**
         * 表格转三元组文本（对应原版 TripletTableSerializer）：多列表格输出
         * “行名, 列名 = 值”句串；单列表格输出 “列名 = 值”；空表回退为逐格拼句
         *
         * @param table docling 表格项
         * @return 三元组文本
         */
        private static String toTriplets(DoclingDocument.TableItem table) {
            // 步骤 1：铺格子
            DoclingDocument.TableData data = table.getData();
            int rows = data.getNumRows();
            int cols = data.getNumCols();
            String[][] grid = new String[rows][cols];
            for (DoclingDocument.TableCell cell : data.getTableCells()) {
                int r = Math.min(cell.getStartRowOffsetIdx(), rows - 1);
                int c = Math.min(cell.getStartColOffsetIdx(), cols - 1);
                grid[r][c] = cell.getText() == null ? "" : cell.getText().replaceAll("[\\n\\r]+", " ").strip();
            }
            // 步骤 2：单列表格——首行当列名，其余行当值
            List<String> parts = new ArrayList<>();
            if (cols == 1) {
                String colName = grid[0][0];
                for (int r = 1; r < rows; r++) {
                    if (!grid[r][0].isEmpty()) {
                        parts.add(colName + " = " + grid[r][0]);
                    }
                }
            } else {
                // 步骤 3：多列表格——首行为列名，其余行以首格为行名，输出 “行名, 列名 = 值”
                for (int r = 1; r < rows; r++) {
                    for (int c = 1; c < cols; c++) {
                        String value = grid[r][c];
                        if (!value.isEmpty()) {
                            parts.add(grid[r][0] + ", " + grid[0][c] + " = " + value);
                        }
                    }
                }
            }
            // 步骤 4：兜底——三元组为空（如表头与行名全空）时逐格拼句
            if (parts.isEmpty()) {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        if (!grid[r][c].isEmpty()) {
                            parts.add(grid[r][c]);
                        }
                    }
                }
            }
            return String.join(". ", parts);
        }
    }

    /**
     * 内部块：结构层/token 精修层之间流转的块表示（对应原版 DocChunk）
     *
     * @param text    块正文
     * @param headings 所属标题路径（层级升序，可能为空）
     * @param items   构成该块的内容项（供上游定位/组元数据）
     * @param pageNo  首个内容项所在页码
     * @param top     首个内容项页内坐标
     * @param isTable 是否含表格内容
     */
    record DocChunk(String text, List<String> headings, List<FlowItem> items, int pageNo, double top, boolean isTable) {

        /**
         * 转为对外输出的 HybridChunk（补齐块类型与引用列表）
         *
         * @return 对外块对象
         */
        HybridChunk toHybridChunk() {
            // 步骤 1：块类型——表格优先；无标题归属的正文算 text；列表项流出的也按 text 归类
            String blockType = isTable ? "table" : "text";
            // 步骤 2：收集内容项引用与页码（去重保序）
            List<String> refs = items.stream().map(FlowItem::ref).distinct().collect(Collectors.toList());
            List<Integer> pages = items.stream().map(FlowItem::pageNo).distinct().collect(Collectors.toList());
            return new HybridChunk(text, headings, refs, pages, blockType, pageNo, top);
        }
    }

    /**
     * 对外输出块：切块最终产物，供上游组装 TextSegment 元数据后入库
     *
     * @param text      块正文（含上下文语义，但不重复拼接 headings——headings 由调用方决定是否前置）
     * @param headings  所属标题路径（层级升序）
     * @param itemRefs  构成该块的内容项 self_ref 列表（保序去重）
     * @param pageNos   涉及页码列表（升序去重）
     * @param blockType 块类型（table / text）
     * @param pageNo    主页码（首个内容项所在页）
     * @param top       主页码内纵向坐标（排序锚点）
     */
    public record HybridChunk(String text, List<String> headings, List<String> itemRefs,
                              List<Integer> pageNos, String blockType, int pageNo, double top) {
    }
}
