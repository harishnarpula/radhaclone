package com.askoxy.radhaai.entity;

import com.askoxy.radhaai.enums.ContentStatus;
import com.askoxy.radhaai.enums.PlatformType;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "content_items")
public class ContentItem extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String contentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawInstruction;

    /** Combined clean inputs: text + voice transcript + AI file summary */
    @Column(columnDefinition = "TEXT")
    private String extractedInputs;

    @Enumerated(EnumType.STRING)
    @Column
    private PlatformType platform;

    @Column
    private String customPlatformName;

    @Column(columnDefinition = "TEXT")
    private String generatedContent;

    @Column(columnDefinition = "TEXT")
    private String editedContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    @Column(columnDefinition = "TEXT")
    private String adminFeedback;

    @Column(columnDefinition = "TEXT")
    private String publishResults;

    @Column(columnDefinition = "TEXT")
    private String selectedChannels;

    @Column(name = "is_grouped")
    private Boolean isGrouped = false;
}