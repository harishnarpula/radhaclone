package com.askoxy.radhaai.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UploadResponse {
    private String fileId;
    private String fileName;
    private Integer chunksStored;
    private String status;
    private Integer totalCharacters;
}
