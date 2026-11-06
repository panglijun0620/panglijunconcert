package com.concert.analytics.repository;

import com.concert.analytics.domain.DataBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataBatchRepository extends JpaRepository<DataBatch, Long> {

    Optional<DataBatch> findFirstByPublishedAtIsNotNullOrderByPublishedAtDescIdDesc();

    List<DataBatch> findTop10ByOrderByStartedAtDesc();
}
