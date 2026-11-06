package com.concert.analytics.controller;

import com.concert.analytics.api.ApiDtos.CacheClearResponse;
import com.concert.analytics.service.CacheAdminService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "concert.security.admin", name = "enabled", havingValue = "true")
public class AdminController {

    private final CacheAdminService cacheAdminService;

    public AdminController(CacheAdminService cacheAdminService) {
        this.cacheAdminService = cacheAdminService;
    }

    @DeleteMapping("/cache")
    public CacheClearResponse clearCache() {
        return cacheAdminService.clearAll();
    }
}
