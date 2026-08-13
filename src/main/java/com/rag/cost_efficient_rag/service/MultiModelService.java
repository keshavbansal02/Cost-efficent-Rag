package com.rag.cost_efficient_rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MultiModelService {

    private final ChatModel geminiChatModel;
    private ChatModel openAiChatModel;

    public MultiModelService(ChatModel geminiChatModel,
                             @Value("${custom.openai.api-key:}") String openAiApiKey,
                             @Value("${custom.openai.model:gpt-4o-mini}") String openAiModel) {
        this.geminiChatModel = geminiChatModel;

        if (openAiApiKey != null && !openAiApiKey.isBlank() && !openAiApiKey.equals("YOUR_OPENAI_API_KEY")) {
            try {
                log.info("Initializing native OpenAI ChatModel for GPT-4o-mini...");
                // Initialize direct OpenAI client targeting api.openai.com
                OpenAiApi openAiApi = new OpenAiApi("https://api.openai.com", openAiApiKey);
                this.openAiChatModel = new OpenAiChatModel(openAiApi);
            } catch (Exception e) {
                log.error("Failed to initialize native OpenAI ChatModel: {}", e.getMessage(), e);
            }
        } else {
            log.info("Native OpenAI API key not set or placeholder detected. Falling back to Gemini for all operations.");
        }
    }

    /**
     * Get the configured ChatModel. Fallback to Gemini if OpenAI is not set up.
     */
    public ChatModel getModel(String preferredProvider) {
        if ("OPENAI".equalsIgnoreCase(preferredProvider) && openAiChatModel != null) {
            log.debug("Using native OpenAI ChatModel (GPT-4o-mini)");
            return openAiChatModel;
        }
        log.debug("Using Gemini ChatModel");
        return geminiChatModel;
    }

    /**
     * Get default model (Gemini).
     */
    public ChatModel getDefaultModel() {
        return geminiChatModel;
    }

    /**
     * Check if native OpenAI model is initialized and ready.
     */
    public boolean isOpenAiReady() {
        return openAiChatModel != null;
    }
}
