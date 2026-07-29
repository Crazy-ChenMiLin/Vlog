package com.tongji.llm.agent.node.anwserNode;

import com.tongji.llm.agent.state.RagAgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectAnswerNode {
    private final ChatClient chatClient;

    /**
     * Handles small-talk or questions that do not need knowledge-base retrieval.
     */
    public String execute(RagAgentState state) {
        String answer = chatClient
                .prompt()
                .system("你是中文助手。用户的问题不需要知识库检索时，直接简洁回答；如果涉及实时信息，请说明无法确认实时状态。")
                .user(state.originalQuestion())
                .options(OpenAiChatOptions.builder()
                        .temperature(0.2)
                        .build())
                .call()
                .content();
        state.finalAnswer(answer);
        return answer;
    }
}
