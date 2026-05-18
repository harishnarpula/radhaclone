package com.askoxy.radhaai.controller;

import com.askoxy.radhaai.dto.ApiResponse;
import com.askoxy.radhaai.dto.ChatRequest;
import com.askoxy.radhaai.dto.ChatResponse;
import com.askoxy.radhaai.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Radha Clone — public AI chatbot.
 * POST /api/v1/radha/chat
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/radha")
public class RadhaController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        return ApiResponse.success(chatService.chat(request));
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("Radha AI is online");
    }
}
