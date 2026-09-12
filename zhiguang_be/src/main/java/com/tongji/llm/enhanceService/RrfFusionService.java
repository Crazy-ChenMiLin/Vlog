package com.tongji.llm.enhanceService;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 RRF（Reciprocal Rank Fusion，倒数排名融合）合并多路召回结果。
 *
 * <p>RRF 只依赖各召回列表中的名次，不要求不同检索器的原始分数处于同一量纲；
 * 同一文档在多路结果中排名越靠前，累计得分越高。</p>
 */
@Component
public class RrfFusionService {


    /** RRF 平滑常数，采用常用默认值 60，降低头部名次之间的分差。 */
    private static final int RRF_K = 60;


    /**
     * 按 {@code 1 / (k + rank)} 累加同一文档在各召回列表中的得分并排序。
     *
     * @param rankedLists 多路召回结果；每个列表均按相关性从高到低排列
     * @param topK 最多返回的文档数，小于 1 时仍保留 1 条
     * @return 按 RRF 得分降序排列的文档列表
     */
    public List<Document> fuse(List<List<Document>> rankedLists, int topK) {


        // 分数和文档分开保存，便于同一文档跨召回列表累积分数。
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> documents = new LinkedHashMap<>();
        for (List<Document> rankedList : rankedLists) {
            for (int index = 0; index < rankedList.size(); index++) {
                Document document = rankedList.get(index);
                String key = documentKey(document);
                // 同一切片可能被多路召回，保留首次出现的文档对象并累计其排名得分。
                documents.putIfAbsent(key, document);
                double score =1.0 / (RRF_K + index + 1);
                scores.merge(
                        key,
                        score,
                        Double::sum
                );
            }
        }



        List<String> keys =new ArrayList<>(documents.keySet());
        keys.sort(
                Comparator
                        .comparingDouble(
                                (String key)
                                        -> scores.getOrDefault(key, 0.0)
                        )
                        .reversed()
                        // 分数相同时按文档标识排序，保证结果可复现。
                        .thenComparing(key -> key)
        );
        return keys.stream()
                .limit(Math.max(1, topK))
                .map(documents::get)
                .toList();
    }



    /**
     * 返回跨召回列表稳定一致的文档标识，用于识别并合并重复切片。
     */
    private String documentKey(Document document) {
        // chunkId 是切片级唯一标识，应优先于文章级 postId。
        Object chunkId =
                document.getMetadata().get("chunkId");


        if (chunkId != null) {
            return String.valueOf(chunkId);
        }

        // 兼容缺少 chunkId 的历史数据，避免同一文章的不同切片被错误合并。
        Object postId =
                document.getMetadata().get("postId");
        return String.valueOf(postId)
                + "|"
                + document.getText();
    }
}
