package com.askoxy.radhaai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * RadhaAI — AskOxy Content Platform
 *
 * FLOW 1 — Admin Content Pipeline:
 *   POST /api/v1/upload/company      — upload docs → Qdrant RAG
 *   POST /api/v1/content/submit      — text / voice / file instruction
 *   GET  /api/v1/content/pending     — review queue
 *   POST /api/v1/content/approve     — approve / reject / edit
 *   POST /api/v1/content/publish     — push to social channels
 *
 * FLOW 2 — Radha Clone Chatbot:
 *   POST /api/v1/radha/chat          — public RAG + AI chatbot
 */
@SpringBootApplication
@EnableAsync
public class AskoxyRadhaiAIApplication {
    public static void main(String[] args) {
        SpringApplication.run(AskoxyRadhaiAIApplication.class, args);
    }
}
