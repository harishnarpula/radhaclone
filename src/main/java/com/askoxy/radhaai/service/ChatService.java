package com.askoxy.radhaai.service;

import com.askoxy.radhaai.dto.ChatRequest;
import com.askoxy.radhaai.dto.ChatResponse;
import com.askoxy.radhaai.enums.PlatformType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * ChatService — Radha Clone chatbot with RAG.
 * Replaces: RadhaCloneService + RetrievalService (retrieval folded in as private method).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AIService aiService;
    private final VectorStore vectorStore;

    private static final String RADHA_SYSTEM = """
            You are Radha, the AI clone of Radha Sir — CEO of the AskOxy group.
            
            You speak with confidence, clarity, and deep domain knowledge about:
            - OxyLoans: lending digitization, NBFC automation
            - OxyBricks: real estate ecosystem
            - OxyGold.ai: gold loans and investments
            - AskOxy.AI: enterprise AI solutions
            - StudyAbroad: overseas education consulting
            - OxyGlobal: global business services
            
            Personality: warm, approachable, knowledgeable. Speak in first person as Radha Sir.
            Give concise, actionable answers. If uncertain, say so. Never invent facts.
            Use the provided knowledge base context to answer accurately.
            """;

    public ChatResponse chat(ChatRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId() : UUID.randomUUID().toString();

        log.info("RadhaClone chat: sessionId={} platform={}", sessionId, request.getPlatform());

        // RAG retrieval — optionally filtered by platform
        List<Document> docs = retrieveDocs(request.getMessage(), request.getPlatform(), 6);
        String context = String.join("\n\n", docs.stream().map(Document::getText).toList());

        String userPrompt = """
                KNOWLEDGE BASE CONTEXT:
                %s
                
                ---
                USER QUESTION: %s
                """.formatted(context, request.getMessage());

        String reply = aiService.chat(RADHA_SYSTEM, userPrompt);

        return ChatResponse.builder()
                .sessionId(sessionId).reply(reply)
                .platform(request.getPlatform()).sourcesUsed(docs.size())
                .build();
    }

    /** RAG similarity search, optionally filtered by platform (clientName metadata). */
    private List<Document> retrieveDocs(String query, String platform, int topK) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);

            // Add platform filter if specified
            if (platform != null && !platform.isBlank()) {
                try {
                    PlatformType.valueOf(platform.toUpperCase()); // validate
                    builder.filterExpression("clientName == '" + platform.toUpperCase() + "'");
                } catch (IllegalArgumentException ignored) {
                    log.warn("Unknown platform filter: {}", platform);
                }
            }

            List<Document> docs = vectorStore.similaritySearch(builder.build());
            log.info("Retrieved {} chunks for query", docs.size());
            return docs;
        } catch (Exception ex) {
            log.warn("RAG retrieval failed: {}", ex.getMessage());
            return List.of();
        }
    }
}
