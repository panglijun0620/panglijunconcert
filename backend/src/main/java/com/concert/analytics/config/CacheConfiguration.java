package com.concert.analytics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class CacheConfiguration implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);
    private final ObjectProvider<CacheManager> cacheManagerProvider;
    private final Map<String, Cache> resilientCaches = new ConcurrentHashMap<>();

    public CacheConfiguration(ObjectProvider<CacheManager> cacheManagerProvider) {
        this.cacheManagerProvider = cacheManagerProvider;
    }

    @Override
    public CacheResolver cacheResolver() {
        return context -> context.getOperation().getCacheNames().stream()
                .map(this::resilientCache)
                .toList();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                warn("read", cache, key, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                warn("write", cache, key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                warn("evict", cache, key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                warn("clear", cache, "*", exception);
            }

            private void warn(String operation, Cache cache, Object key, RuntimeException exception) {
                log.warn("Cache {} failed for cache={} key={}; continuing without cache: {}",
                        operation, cache.getName(), key, exception.getMessage());
            }
        };
    }

    private Cache resilientCache(String name) {
        return resilientCaches.computeIfAbsent(name, cacheName -> {
            Cache delegate = cacheManagerProvider.getObject().getCache(cacheName);
            return new ResilientSyncCache(Objects.requireNonNull(delegate, "Unknown cache " + cacheName));
        });
    }

    private static final class ResilientSyncCache implements Cache {
        private static final int LOCK_STRIPES = 64;

        private final Cache delegate;
        private final Object[] locks = new Object[LOCK_STRIPES];

        private ResilientSyncCache(Cache delegate) {
            this.delegate = delegate;
            for (int index = 0; index < locks.length; index++) {
                locks[index] = new Object();
            }
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            return delegate.get(key);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return delegate.get(key, type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            Object lock = locks[Math.floorMod(Objects.hashCode(key), locks.length)];
            synchronized (lock) {
                try {
                    ValueWrapper cached = delegate.get(key);
                    if (cached != null) {
                        return (T) cached.get();
                    }
                } catch (RuntimeException exception) {
                    warn("read", delegate, key, exception);
                }

                T value;
                try {
                    value = valueLoader.call();
                } catch (Exception exception) {
                    throw new ValueRetrievalException(key, valueLoader, exception);
                }

                if (value != null) {
                    try {
                        delegate.put(key, value);
                    } catch (RuntimeException exception) {
                        warn("write", delegate, key, exception);
                    }
                }
                return value;
            }
        }

        @Override
        public void put(Object key, Object value) {
            delegate.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static void warn(String operation, Cache cache, Object key, RuntimeException exception) {
        log.warn("Cache {} failed for cache={} key={}; continuing without cache: {}",
                operation, cache.getName(), key, exception.getMessage());
    }
}
