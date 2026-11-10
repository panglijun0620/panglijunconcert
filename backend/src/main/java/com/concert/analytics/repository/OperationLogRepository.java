package com.concert.analytics.repository;

import com.concert.analytics.domain.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
