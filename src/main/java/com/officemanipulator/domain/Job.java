package com.officemanipulator.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@Setter
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutputFormat format;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String request;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "files_cleaned_at")
    private OffsetDateTime filesCleanedAt;

    @Column(name = "failed_at")
    private String failedAt;

    @Column(name = "token_input")
    private Integer tokenInput;

    @Column(name = "token_output")
    private Integer tokenOutput;

    @Column(name = "cost_usd", precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "image_paths_json", columnDefinition = "TEXT")
    private String imagePathsJson;

    @Column(name = "plan_confirmed_at")
    private OffsetDateTime planConfirmedAt;

    /** Visual/narrative design decisions chosen by the design-director Claude call, before any
     * content is written — only set for presentations. See ClaudeOrchestrator.runDesignDirection. */
    @Column(name = "design_direction_json", columnDefinition = "TEXT")
    private String designDirectionJson;

    /** Set on edit/rebuild jobs to the job they revised; null for top-level, history-visible jobs. */
    @Column(name = "parent_job_id")
    private UUID parentJobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(72);
}
