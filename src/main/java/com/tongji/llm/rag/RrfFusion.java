package com.tongji.llm.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：根据文档在多路召回结果中的排名进行融合。
 */
@Component
public class RrfFusion {
    private static final int RRF_K = 60;

    public List<Document> fuse(List<List<Document>> rankedLists, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> documents = new LinkedHashMap<>();

        for (List<Document> rankedList : rankedLists) {
            for (int index = 0; index < rankedList.size(); index++) {
                Document document = rankedList.get(index);
                String key = documentKey(document);
                documents.putIfAbsent(key, document);
                scores.merge(key, 1.0 / (RRF_K + index + 1), Double::sum);
            }
        }

        List<String> keys = new ArrayList<>(documents.keySet());
        keys.sort(Comparator
                .comparingDouble((String key) -> scores.getOrDefault(key, 0.0))
                .reversed()
                .thenComparing(key -> key));

        return keys.stream()
                .limit(Math.max(1, topK))
                .map(documents::get)
                .toList();
    }

    private String documentKey(Document document) {
        Object chunkId = document.getMetadata().get("chunkId");
        if (chunkId != null) {
            return String.valueOf(chunkId);
        }
        Object postId = document.getMetadata().get("postId");
        return String.valueOf(postId) + "|" + document.getText();
    }
}
