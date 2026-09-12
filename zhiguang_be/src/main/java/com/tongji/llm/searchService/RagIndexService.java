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
 * 构建并维护知文的 RAG 向量索引。
 *
 * <p>仅索引公开且已发布的知文。内容指纹未变化时跳过重建；需要重建时，
 * 先按 {@code postId} 清理旧切片，再批量写入新切片，避免新旧内容并存。</p>
 */
@Service
@RequiredArgsConstructor
public class RagIndexService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final String INDEX_VERSION = "utf8-v2";
    /** 负责生成向量并将文档写入 Elasticsearch。 */
    private final VectorStore vectorStore;
    /** 提供正文地址、内容指纹和发布状态等索引源数据。 */
    private final KnowPostMapper knowPostMapper;
    /** 下载远程 Markdown 正文。 */
    private final RestTemplate http = new RestTemplate();
    /** 用于查询内容指纹以及按文章删除旧切片。 */
    private final ElasticsearchClient es;
    /** Elasticsearch 索引配置。 */
    private final EsProperties esProps;

    public void ensureIndexed(long postId) {
        // 指纹检查使该操作可安全重复调用，适合在问答前保证索引已就绪。
        reindexSinglePost(postId);
    }

    public int reindexSinglePost(long postId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null) {
            log.warn("Post {} not found", postId);
            deletePost(postId);
            return 0;
        }

        // 状态变为不可检索后同步删除已有索引，避免继续召回私密或草稿内容。
        if (!"published".equalsIgnoreCase(row.getStatus()) || !"public".equalsIgnoreCase(row.getVisible())) {
            log.warn("Post {} is not public/published, skip indexing", postId);
            deletePost(postId);
            return 0;
        }

        if (!StringUtils.hasText(row.getContentUrl())) {
            log.warn("Post {} missing contentUrl or not found", postId);
            return 0;
        }

        String currentSha = row.getContentSha256();
        String currentEtag = row.getContentEtag();
        if (isUpToDate(postId, currentSha, currentEtag)) {
            log.info("Post {} already indexed with same fingerprint, skip", postId);
            return 0;
        }

        String text = fetchContent(row.getContentUrl());
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "知文正文为空或无法读取");
        }

        List<RagChunk> chunks = chunkMarkdown(text);
        // VectorStore 不提供文章级替换语义，因此写入前显式删除该文章的全部旧切片。
        deletePost(postId);

        // 元数据同时服务于检索过滤、结果展示和下一次内容指纹检查。
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
            vectorStore.add(docs);
        } catch (Exception e) {
            log.error("VectorStore add failed for post {}: {}", postId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.RAG_INDEX_FAILED, "知识索引写入失败");
        }
        return docs.size();
    }

    /**
     * 判断现有索引是否对应当前内容版本。
     *
     * <p>索引版本必须一致；内容指纹优先比较 SHA-256，缺失时再比较 ETag。
     * 查询失败时返回 {@code false}，由调用方执行重建以保证数据正确性。</p>
     */
    private boolean isUpToDate(long postId, String currentSha, String currentEtag) {
        try {
            if (!StringUtils.hasText(esProps.getIndex())) {
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

    /** 按 {@code metadata.postId} 删除指定知文的全部索引切片。 */
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
     * 将各 Markdown 章节切成不超过 800 字符的片段，相邻片段重叠 100 字符以保留语义连续性。
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
