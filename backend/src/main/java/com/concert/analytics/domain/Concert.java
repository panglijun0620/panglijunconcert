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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "clean_concert")
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_batch_id")
    private DataBatch dataBatch;

    @Column(name = "artist_name", nullable = false, length = 120)
    private String artistName;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(name = "concert_name", nullable = false, length = 240)
    private String concertName;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(nullable = false, length = 180)
    private String venue;

    @Column(name = "show_time", nullable = false)
    private LocalDateTime showTime;

    @Column(name = "show_time_precision", nullable = false, length = 16)
    private String showTimePrecision;

    @Column(name = "price_text", nullable = false, length = 240)
    private String priceText;

    @Column(name = "min_price", precision = 10, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 10, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "sale_status", nullable = false, length = 40)
    private String saleStatus;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_record_id", length = 160)
    private String sourceRecordId;

    @Column(name = "source_url", nullable = false, length = 800)
    private String sourceUrl;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Concert() {
    }

    public Long getId() {
        return id;
    }

    public DataBatch getDataBatch() {
        return dataBatch;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getCategory() {
        return category;
    }

    public String getConcertName() {
        return concertName;
    }

    public String getCity() {
        return city;
    }

    public String getVenue() {
        return venue;
    }

    public LocalDateTime getShowTime() {
        return showTime;
    }

    public String getShowTimePrecision() {
        return showTimePrecision;
    }

    public String getPriceText() {
        return priceText;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public String getSaleStatus() {
        return saleStatus;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
