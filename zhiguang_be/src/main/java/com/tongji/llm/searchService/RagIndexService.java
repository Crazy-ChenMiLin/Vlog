package com.tongji.llm.searchService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.config.EsProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RAG 索引构建服务：
 * - 将公开且已发布的知文切片并写入向量库
 * - 通过指纹（SHA256/ETag）判断是否需要重建，保证幂等
 * - 采用 delete-by-query 清理旧切片，再批量 upsert 新切片
 */
@Service
@RequiredArgsConstructor
public class RagIndexService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final String INDEX_VERSION = "utf8-v2";
    // 向量库封装（Elasticsearch VectorStore），负责写入/检索向量
    private final VectorStore vectorStore;
    // 数据访问：根据 postId 查询知文详情（含 contentUrl、指纹等）
    private final KnowPostMapper knowPostMapper;
    // 拉取 Markdown 正文内容
    private final RestTemplate http = new RestTemplate();
    // 直接使用 ES 客户端做指纹判断和删除旧切片
    private final ElasticsearchClient es;
    // ES 相关配置（索引名等）
    private final EsProperties esProps;

    public void ensureIndexed(long postId) {
        // 当前策略：在问答前直接尝试重建（指纹未变化时会跳过）
        reindexSinglePost(postId);
    }

    public int reindexSinglePost(long postId) {
        // 步骤1：查知文详情（比如帖子123的标题、内容链接、指纹）
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null) {
            log.warn("Post {} not found", postId);
            deletePost(postId);
            return 0;
        }

        // 仅索引公开的已发布知文
        // 步骤2：只处理“公开+已发布”的知文（私密/草稿不存）
        if (!"published".equalsIgnoreCase(row.getStatus()) || !"public".equalsIgnoreCase(row.getVisible())) {
            log.warn("Post {} is not public/published, skip indexing", postId);
            deletePost(postId);
            return 0;
        }

        // 步骤3：没内容链接，下载不了正文，直接跳过
        if (!StringUtils.hasText(row.getContentUrl())) {
            log.warn("Post {} missing contentUrl or not found", postId);
            return 0;
        }

        // 步骤4：指纹校验（关键！避免重复干活）
        String currentSha = row.getContentSha256();// 内容的“指纹1”
        String currentEtag = row.getContentEtag();// 内容的“指纹2”
        if (isUpToDate(postId, currentSha, currentEtag)) {
            log.info("Post {} already indexed with same fingerprint, skip", postId);
            return 0;
        }

        // 步骤5：下载知文的Markdown正文（比如从链接下载“Java入门.md”的内容）
        String text = fetchContent(row.getContentUrl());
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "知文正文为空或无法读取");
        }

        // 先按 Markdown 标题切段，再做固定长度切片（带重叠）
        // 步骤6：把正文切成小块（切片）
        List<RagChunk> chunks = chunkMarkdown(text);
        // 幂等 upsert：先删除旧切片
        // 步骤7：先删旧卡片（避免旧内容残留）
        deletePost(postId);

        // 组装 Document（文本 + 业务元数据），用于向量写入与检索过滤
        // 步骤8：给每个小块加“标签”（元数据），组装成Document（向量库能识别的格式）
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String cid = postId + "#" + i;
            Map<String, Object> meta = new HashMap<>();
            meta.put("postId", String.valueOf(postId));
            meta.put("chunkId", cid);
            meta.put("position", i);
            meta.put("contentEtag", currentEtag);
            meta.put("contentSha256", currentSha);
            meta.put("indexVersion", INDEX_VERSION);
            meta.put("contentUrl", row.getContentUrl());
            meta.put("title", row.getTitle());
            RagChunk chunk = chunks.get(i);
            meta.put("sectionTitle", chunk.sectionTitle());
            meta.put("sectionType", chunk.sectionType());
            docs.add(new Document(chunk.text(), meta));
        }
        try {
            // 批量写入向量库
            vectorStore.add(docs);
        } catch (Exception e) {
            log.error("VectorStore add failed for post {}: {}", postId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "知识索引写入失败");
        }
        // 返回本次写入的切片数量
        return docs.size();
    }

    /**
     * 指纹判断是否需要重建：
     * - 以 postId 查询任意一条已索引文档的 metadata
     * - 优先比较 SHA256，其次比较 ETag；一致则视为无需重建
     */
    private boolean isUpToDate(long postId, String currentSha, String currentEtag) {
        try {
            if (!StringUtils.hasText(esProps.getIndex())) {
                // 未配置索引名则无法判断，直接视为需要重建
                return false;
            }
            SearchResponse<Map> resp = es.search(s -> s
                            .index(esProps.getIndex())
                            .size(1)
                            .query(q -> q.term(t -> t
                                    .field("metadata.postId")
                                    .value(v -> v.stringValue(String.valueOf(postId))))),
                    Map.class);
            List<Hit<Map>> hits = resp.hits().hits();
            if (hits == null || hits.isEmpty()) return false;
            Map source = hits.getFirst().source();
            if (source == null) return false;
            Object metaObj = source.get("metadata");
            if (!(metaObj instanceof Map<?, ?> meta)) return false;
            String indexedSha = asString(meta.get("contentSha256"));
            String indexedEtag = asString(meta.get("contentEtag"));
            String indexVersion = asString(meta.get("indexVersion"));
            if (!Objects.equals(INDEX_VERSION, indexVersion)) {
                return false;
            }
            if (StringUtils.hasText(currentSha) && StringUtils.hasText(indexedSha)) {
                return Objects.equals(currentSha, indexedSha);
            }
            if (StringUtils.hasText(currentEtag) && StringUtils.hasText(indexedEtag)) {
                return Objects.equals(currentEtag, indexedEtag);
            }
            return false;
        } catch (Exception e) {
            log.warn("Fingerprint check failed for post {}: {}", postId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除旧切片：按 metadata.postId 精确删除，确保 upsert 幂等
     */
    public void deletePost(long postId) {
        try {
            if (!StringUtils.hasText(esProps.getIndex())) {
                throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "未配置向量索引名称");
            }
            es.deleteByQuery(d -> d
                    .index(esProps.getIndex())
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(q -> q.term(t -> t
                            .field("metadata.postId")
                            .value(v -> v.stringValue(String.valueOf(postId))))));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Delete chunks failed for post {} from index {}: {}",
                    postId, esProps.getIndex(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "旧知识切片删除失败");
        }
    }

    private static String asString(Object o) {
        // 统一处理 null → String 的转换
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 拉取正文内容（Markdown 文本）。
     */
    private String fetchContent(String url) {
        try {
            byte[] bytes = http.getForObject(url, byte[].class);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Fetch content failed from {}: {}", url, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 按 Markdown 标题切段，再交由固定长度切片策略处理。
     */
    private List<RagChunk> chunkMarkdown(String text) {
        List<RagSection> sections = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        StringBuilder buf = new StringBuilder();
        String currentTitle = "";
        for (String line : lines) {
            boolean isHeader = line.startsWith("#");
            if (isHeader && !buf.isEmpty()) { // 遇到新的标题，收束上一段
                sections.add(new RagSection(buf.toString(), currentTitle));
                buf.setLength(0);
            }
            if (isHeader) {
                currentTitle = normalizeMarkdownHeader(line);
            }
            buf.append(line).append('\n');
        }
        if (!buf.isEmpty()) sections.add(new RagSection(buf.toString(), currentTitle));

        return getChunks(sections);
    }

    /**
     * 固定长度切片（每片 ≤ 800 字符），切片间 100 字符重叠：
     * - 兼顾检索召回与上下文连续性
     */
    private static List<RagChunk> getChunks(List<RagSection> sections) {
        List<RagChunk> chunks = new ArrayList<>();
        for (RagSection section : sections) {
            String p = section.text();
            String sectionTitle = section.sectionTitle();
            String sectionType = classifySectionType(sectionTitle);
            if (p.length() <= 800) {
                chunks.add(new RagChunk(p, sectionTitle, sectionType));
            } else {
                int start = 0;
                while (start < p.length()) {
                    int end = Math.min(start + 800, p.length());
                    chunks.add(new RagChunk(p.substring(start, end), sectionTitle, sectionType));
                    if (end >= p.length()) break;
                    start = Math.max(end - 100, start + 1); // 重叠 100 字符以保留语义连续
                }
            }
        }
        return chunks;
    }

    private static String normalizeMarkdownHeader(String line) {
        return line == null ? "" : line.replaceFirst("^#+\\s*", "").trim();
    }

    private static String classifySectionType(String sectionTitle) {
        if (!StringUtils.hasText(sectionTitle)) {
            return "OTHER";
        }
        if (sectionTitle.contains("核心概念")) {
            return "CONCEPT";
        }
        if (sectionTitle.contains("背景")) {
            return "BACKGROUND";
        }
        if (sectionTitle.contains("面试回答模板")) {
            return "INTERVIEW_TEMPLATE";
        }
        if (sectionTitle.contains("测试问题")) {
            return "TEST_QUESTION";
        }
        if (sectionTitle.contains("常见误区") || sectionTitle.contains("坑")) {
            return "PITFALL";
        }
        if (sectionTitle.contains("解决") || sectionTitle.contains("方案") || sectionTitle.contains("排查")) {
            return "SOLUTION";
        }
        return "OTHER";
    }

    private record RagSection(String text, String sectionTitle) {
    }

    private record RagChunk(String text, String sectionTitle, String sectionType) {
    }
}
