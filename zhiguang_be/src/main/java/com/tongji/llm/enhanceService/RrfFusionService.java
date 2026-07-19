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
 * RRF（Reciprocal Rank Fusion，倒数排名融合）
 * 核心思想：
 * 一个文档如果在多个召回结果中都排名靠前，
 * 那么它更可能是用户需要的内容。
 */
@Component
public class RrfFusionService {


    /**
     * RRF 平滑参数
     * 通常业界默认使用 60。
     */
    private static final int RRF_K = 60;


    /**
     * 多路召回结果融合。
     *
     * 当前 RAG 不是 BM25 + 向量的混合召回，而是两路向量召回：
     * 1. 原始问题的向量检索结果
     * 2. HyDE 假设答案的向量检索结果
     *
     * 如果后续接入 BM25 关键词召回，也可以继续复用这个 RRF 融合逻辑。
     *
     * @param rankedLists 多个召回结果列表
     * [
     *    [A,B,C],   // 原问题向量检索结果
     *    [B,D,A]    // HyDE 答案向量检索结果
     * ]
     * @param topK 最终保留多少个文档
     * @return RRF排序后的文档列表
     */
    public List<Document> fuse(List<List<Document>> rankedLists, int topK) {


        /*
         * 保存每个文档最终的RRF分数
         * key:
         * 文档唯一id
         * value:
         * RRF计算后的分数
         */
        Map<String, Double> scores = new HashMap<>();


        /*
         * 保存文档对象本身
         * 为什么需要单独保存？
         * 因为scores里面只保存：
         * 文档id -> 分数
         * 最后返回结果时，
         * 还需要通过id找到真正的Document对象。
         *
         * LinkedHashMap:
         * 保证插入顺序。
         */
        Map<String, Document> documents = new LinkedHashMap<>();


        /*
         * 遍历每一路召回结果
         * 例如：
         * 第一次循环：
         * 原问题向量检索结果
         * 第二次循环：
         * HyDE 答案向量检索结果
         */
        for (List<Document> rankedList : rankedLists) {


            /*
             * 遍历当前召回列表
             * index代表排名：
             * index=0  第一名
             * index=1  第二名
             * index=2  第三名
             *
             */
            for (int index = 0; index < rankedList.size(); index++) {


                // 当前排名对应的文档
                Document document = rankedList.get(index);
                //获取文档唯一id标识
                String key = documentKey(document);
                /*
                 * 保存文档对象
                 * putIfAbsent:
                 * 如果这个key不存在，则保存。
                 * 如果已经存在，不覆盖。
                 * 原因：
                 * 同一个文档可能被多个召回方式找到。
                 */
                documents.putIfAbsent(key, document);
                double score =1.0 / (RRF_K + index + 1);
                /*
                 * 累加分数
                 * merge作用：
                 * 如果第一次出现：
                 * docA = score
                 * 第二次出现：
                 * docA = 原分数 + 新分数
                 * 因为：
                 * 一个文档在多个检索器中出现，
                 * 需要累加奖励。
                 */
                scores.merge(
                        key,
                        score,
                        Double::sum
                );
            }
        }



        /*
         * 获取所有文档id
         * 后面根据RRF分数排序。
         */
        List<String> keys =new ArrayList<>(documents.keySet());

        /*
         * 根据RRF分数倒序排列
         * 分数越高，
         * 说明这个文档综合排名越靠前。
         */
        keys.sort(
                Comparator
                        .comparingDouble(
                                (String key)
                                        -> scores.getOrDefault(key, 0.0)
                        )
                        .reversed()
                         // 如果两个文档分数一样，
                         // 按id排序保证结果稳定。
                        .thenComparing(key -> key)
        );



        /*
         * 取前topK个文档,A,b,c,d
         * topK=2---->AB
         */
        return keys.stream()
                .limit(Math.max(1, topK))
                .map(documents::get)
                .toList();
    }



    /**
     * 获取文档唯一标识
     * 因为多路召回可能找到同一个文档。
     * 原问题向量检索找到：
     * chunk001
     * HyDE 答案向量检索也找到：
     * chunk001
     *
     * RRF需要知道：
     * "这是同一个文档然后给它累加分数。
     */
    private String documentKey(Document document) {


         // 优先使用chunkId,RAG通常会把长文档切成chunk。每个chunk都有唯一id。
        Object chunkId =
                document.getMetadata().get("chunkId");


        if (chunkId != null) {
            return String.valueOf(chunkId);
        }




        //如果没有chunkId， 使用postId + 文本内容作为备用唯一标识。
        Object postId =
                document.getMetadata().get("postId");
        return String.valueOf(postId)
                + "|"
                + document.getText();
    }
}
