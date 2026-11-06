package com.concert.analytics.service;

import com.concert.analytics.domain.DataBatch;
import com.concert.analytics.repository.DataBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service("datasetVersionService")
public class DatasetVersionService {

    private final DataBatchRepository dataBatchRepository;

    public DatasetVersionService(DataBatchRepository dataBatchRepository) {
        this.dataBatchRepository = dataBatchRepository;
    }

    @Transactional(readOnly = true)
    public String currentVersion() {
        return latestBatch().map(DataBatch::getDatasetVersion).orElse("empty");
    }

    @Transactional(readOnly = true)
    public Optional<DataBatch> latestBatch() {
        return dataBatchRepository.findFirstByPublishedAtIsNotNullOrderByPublishedAtDescIdDesc();
    }
}
