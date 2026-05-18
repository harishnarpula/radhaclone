package com.askoxy.radhaai.dto;

import com.askoxy.radhaai.enums.PlatformType;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;

/** Unified submit request — text / voice / file. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContentRequest {
    private String rawInstruction;
    private PlatformType platform;
    private String customPlatformName; // filled only when platform = OTHER
    private MultipartFile voiceFile;
    private MultipartFile attachment;
}
