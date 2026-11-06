package com.concert.analytics.service;

import com.concert.analytics.api.ApiDtos.CacheClearResponse;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CacheAdminService {

    private final CacheManager cacheManager;

    public CacheAdminService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public CacheClearResponse clearAll() {
        List<String> cleared = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            try {
                Cache cache = cacheManager.getCache(name);
                if (cache != null) {
                    cache.clear();
                    cleared.add(name);
                }
            } catch (RuntimeException exception) {
                failed.add(name);
            }
        }
        return new CacheClearResponse(List.copyOf(cleared), List.copyOf(failed), Instant.now());
    }
}
