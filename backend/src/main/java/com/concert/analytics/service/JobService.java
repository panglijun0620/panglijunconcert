package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.JobItem;
import com.concert.analytics.api.ApiDtos.PageResponse;
import com.concert.analytics.domain.JobRun;
import com.concert.analytics.repository.JobRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    private final JobRunRepository jobRunRepository;

    public JobService(JobRunRepository jobRunRepository) {
        this.jobRunRepository = jobRunRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobItem> jobs(int page, int size) {
        Page<JobRun> jobs = jobRunRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))));
        return PageResponse.from(jobs, DtoMapper::job);
    }
}
