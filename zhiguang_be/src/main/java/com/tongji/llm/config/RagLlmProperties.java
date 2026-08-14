package com.tongji.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.llm")
public class RagLlmProperties {
    private String defaultModel;
    private String plannerModel;
    private String graphModel;
    private String evidenceModel;
    private String rewriteModel;
    private String hydeModel;
    private String directAnswerModel;
    private String finalAnswerModel;

    public String defaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String plannerModel() {
        return modelOrDefault(plannerModel);
    }

    public void setPlannerModel(String plannerModel) {
        this.plannerModel = plannerModel;
    }

    public String graphModel() {
        return modelOrDefault(graphModel);
    }

    public void setGraphModel(String graphModel) {
        this.graphModel = graphModel;
    }

    public String evidenceModel() {
        return modelOrDefault(evidenceModel);
    }

    public void setEvidenceModel(String evidenceModel) {
        this.evidenceModel = evidenceModel;
    }

    public String rewriteModel() {
        return modelOrDefault(rewriteModel);
    }

    public void setRewriteModel(String rewriteModel) {
        this.rewriteModel = rewriteModel;
    }

    public String hydeModel() {
        return modelOrDefault(hydeModel);
    }

    public void setHydeModel(String hydeModel) {
        this.hydeModel = hydeModel;
    }

    public String directAnswerModel() {
        return modelOrDefault(directAnswerModel);
    }

    public void setDirectAnswerModel(String directAnswerModel) {
        this.directAnswerModel = directAnswerModel;
    }

    public String finalAnswerModel() {
        return modelOrDefault(finalAnswerModel);
    }

    public void setFinalAnswerModel(String finalAnswerModel) {
        this.finalAnswerModel = finalAnswerModel;
    }

    private String modelOrDefault(String model) {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return defaultModel == null ? null : defaultModel.trim();
    }
}
