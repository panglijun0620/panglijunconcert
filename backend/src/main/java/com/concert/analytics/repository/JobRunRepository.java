package com.concert.analytics.repository;

import com.concert.analytics.domain.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {
}
