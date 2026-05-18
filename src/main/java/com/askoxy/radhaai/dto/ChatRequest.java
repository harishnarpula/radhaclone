package com.askoxy.radhaai.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatRequest {
    private String message;
    private String platform;   // optional: filter RAG by platform
    private String sessionId;
}
