package com.askoxy.radhaai.service;

import com.askoxy.radhaai.dto.UploadResponse;
import com.askoxy.radhaai.entity.UploadedFile;
import com.askoxy.radhaai.enums.PlatformType;
import com.askoxy.radhaai.enums.UploadStatus;
import com.askoxy.radhaai.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * IngestionService — full upload pipeline: detect → extract → chunk → embed → Qdrant.
 * Replaces: CompanyKnowledgeUploadService + KnowledgeIngestionService + VectorStorageService.
 * CompanyKnowledge entity removed (UploadedFile already holds platform info).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final DocumentService documentService;
    private final VectorStore vectorStore;
    private final UploadedFileRepository uploadedFileRepository;

    private static final int BATCH_SIZE = 10;
    private static final String KNOWLEDGE_TYPE = "COMPANY_KNOWLEDGE";

    @Transactional
    public UploadResponse upload(MultipartFile file, PlatformType platformType, String description) {
        if (file == null || file.isEmpty()) throw new RuntimeException("Uploaded file is empty");

        String fileId = UUID.randomUUID().toString();
        String fileType = documentService.detectFileType(file).name();

        log.info("Upload start: fileId={} platform={} type={}", fileId, platformType, fileType);

        // Save initial DB record
        UploadedFile record = UploadedFile.builder()
                .fileId(fileId)
                .fileName(file.getOriginalFilename())
                .knowledgeType(KNOWLEDGE_TYPE)
                .fileType(fileType)
                .uploadStatus(UploadStatus.UPLOADING)
                .clientName(platformType.name())
                .campaignId("COMPANY_WIDE")
                .vectorStoreId(null)
                .totalChunks(0)
                .build();
        uploadedFileRepository.save(record);

        try {
            // STEP 1 — Extract
            updateStatus(record, UploadStatus.PROCESSING);
            String text = documentService.extractText(file);
            updateStatus(record, UploadStatus.EXTRACTED);

            // STEP 2 — Chunk
            List<DocumentService.Chunk> chunks = documentService.chunk(text);
            updateStatus(record, UploadStatus.CHUNKED);
            log.info("Chunked: fileId={} chunks={}", fileId, chunks.size());

            // STEP 3 — Embed + store in Qdrant
            String vectorStoreId = UUID.randomUUID().toString();
            List<Document> docs = chunks.stream()
                    .filter(c -> c.text() != null && !c.text().isBlank())
                    .map(c -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("fileId", fileId);
                        meta.put("vectorStoreId", vectorStoreId);
                        meta.put("knowledgeType", KNOWLEDGE_TYPE);
                        meta.put("clientName", platformType.name());
                        return new Document(c.text(), meta);
                    }).toList();

            batchStore(docs, fileId);
            updateStatus(record, UploadStatus.STORED);

            // Update record
            record.setVectorStoreId(vectorStoreId);
            record.setTotalChunks(chunks.size());
            uploadedFileRepository.save(record);

            int totalChars = chunks.stream().mapToInt(DocumentService.Chunk::charCount).sum();
            log.info("Upload complete: fileId={} chunks={}", fileId, chunks.size());

            return UploadResponse.builder()
                    .fileId(fileId)
                    .fileName(file.getOriginalFilename())
                    .chunksStored(chunks.size())
                    .status("COMPLETED")
                    .totalCharacters(totalChars)
                    .build();

        } catch (Exception ex) {
            updateStatus(record, UploadStatus.FAILED);
            log.error("Upload failed: fileId={}", fileId, ex);
            throw ex;
        }
    }

    /** Store approved content back into Qdrant knowledge base. */
    public String storeContent(String contentId, String text, String platform) {
        String vectorStoreId = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileId", contentId);
        meta.put("vectorStoreId", vectorStoreId);
        meta.put("knowledgeType", KNOWLEDGE_TYPE);
        meta.put("clientName", platform);
        batchStore(List.of(new Document(text, meta)), contentId);
        return vectorStoreId;
    }

    private void batchStore(List<Document> docs, String fileId) {
        List<List<Document>> batches = partition(docs, BATCH_SIZE);
        for (int i = 0; i < batches.size(); i++) {
            try {
                vectorStore.add(batches.get(i));
                log.debug("Stored batch {}/{} for fileId={}", i + 1, batches.size(), fileId);
            } catch (Exception ex) {
                log.error("Batch {}/{} failed for fileId={} — skipping", i + 1, batches.size(), fileId, ex);
            }
        }
    }

    private void updateStatus(UploadedFile record, UploadStatus status) {
        record.setUploadStatus(status);
        uploadedFileRepository.save(record);
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            out.add(list.subList(i, Math.min(i + size, list.size())));
        return out;
    }
}
