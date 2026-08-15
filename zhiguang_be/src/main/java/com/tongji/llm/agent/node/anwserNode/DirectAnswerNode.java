package com.tongji.llm.agent.node.anwserNode;

import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectAnswerNode {
    private final ChatClient chatClient;
    private final RagLlmProperties ragLlmProperties;
    private final RagPromptService ragPromptService;

    /**
     * Handles small-talk or questions that do not need knowledge-base retrieval.
     */
    public String execute(RagAgentState state) {
        String answer = chatClient
                .prompt()
                .system(ragPromptService.getSystemPrompt(RagPromptService.KEY_DIRECT_ANSWER))
                .user(state.originalQuestion())
                .options(OpenAiChatOptions.builder()
                        .model(ragLlmProperties.directAnswerModel())
                        .temperature(0.2)
                        .build())
                .call()
                .content();
        state.finalAnswer(answer);
        return answer;
    }
}
