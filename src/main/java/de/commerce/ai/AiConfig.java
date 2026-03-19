package de.commerce.ai;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for AI model beans: Ollama embeddings and Google Gemini chat.
 */
@Configuration
public class AiConfig {

    @Bean
    OllamaEmbeddingModel embeddingModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.embedding-model}") String modelName
    ) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    @Bean
    GoogleAiGeminiChatModel geminiChatModel(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.model}") String modelName
    ) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .build();
    }
}
