package com.concert.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "clean_comment")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id")
    private Concert concert;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_batch_id")
    private DataBatch dataBatch;

    @Column(name = "artist_name", length = 120)
    private String artistName;

    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    private String commentText;

    @Column(name = "comment_time")
    private Instant commentTime;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "user_region", length = 80)
    private String userRegion;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_record_id", length = 160)
    private String sourceRecordId;

    @Column(name = "source_url", nullable = false, length = 800)
    private String sourceUrl;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @DecimalMin("-1.0")
    @DecimalMax("1.0")
    @Column(name = "sentiment_score", precision = 6, scale = 5)
    private BigDecimal sentimentScore;

    @Column(name = "sentiment_method", nullable = false, length = 32)
    private String sentimentMethod;

    @Column(name = "primary_topic", length = 100)
    private String primaryTopic;

    protected Comment() {
    }

    public Long getId() {
        return id;
    }

    public Concert getConcert() {
        return concert;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getCommentText() {
        return commentText;
    }

    public Instant getCommentTime() {
        return commentTime;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public String getUserRegion() {
        return userRegion;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public BigDecimal getSentimentScore() {
        return sentimentScore;
    }

    public String getSentimentMethod() {
        return sentimentMethod;
    }

    public String getPrimaryTopic() {
        return primaryTopic;
    }
}
