/*
 *
 * Copyright (c) 2011. All Rights Reserved. Oracle Corporation.
 *
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * This software is the confidential and proprietary information of Oracle
 * Corporation. You shall not disclose such confidential and proprietary
 * information and shall use it only in accordance with the terms of the license
 * agreement you entered into with Oracle Corporation.
 *
 * Oracle Corporation makes no representations or warranties about the
 * suitability of the software, either express or implied, including but not
 * limited to the implied warranties of merchantability, fitness for a
 * particular purpose, or non-infringement. Oracle Corporation shall not be
 * liable for any damages suffered by licensee as a result of using, modifying
 * or distributing this software or its derivatives.
 *
 * This notice may not be removed or altered.
 */
package com.tangosol.coherence.jsr107;

import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.DefaultConfigurableCacheFactory;
import org.jsr107.ri.AbstractCacheManager;
import org.jsr107.ri.DelegatingCacheBuilder;

import javax.cache.Cache;
import javax.cache.CacheBuilder;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.OptionalFeature;
import javax.cache.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ycosmado
 * @since 1.0
 */
class CoherenceCacheManager extends AbstractCacheManager implements CacheManager {
    private static final Logger LOGGER = Logger.getLogger("javax.cache");
    private final HashMap<String, Cache<?, ?>> caches = new HashMap<String, Cache<?, ?>>();
    private volatile Status status;
    private final ConfigurableCacheFactory dccf;

    CoherenceCacheManager(ClassLoader classLoader, String name) {
        super(name, classLoader);
        status = Status.UNINITIALISED;
        if (classLoader == null) {
            throw new NullPointerException("No classLoader specified");
        }
        if (name == null) {
            throw new NullPointerException("No name specified");
        }
        dccf = new DefaultConfigurableCacheFactory();
        status = Status.STARTED;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public <K, V> CacheBuilder<K, V> createCacheBuilder(String cacheName) {
        return new CoherenceCacheBuilder<K, V>(cacheName);
    }

    @Override
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        synchronized (caches) {
            return (Cache<K, V>) caches.get(cacheName);
        }
    }

    @Override
    public Iterable<Cache<?, ?>> getCaches() {
        synchronized (caches) {
            HashSet<Cache<?, ?>> set = new HashSet<Cache<?, ?>>();
            for (Cache<?, ?> cache : caches.values()) {
                set.add(cache);
            }
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public boolean removeCache(String cacheName) throws IllegalStateException {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        if (cacheName == null) {
            throw new NullPointerException();
        }
        Cache oldCache;
        synchronized (caches) {
            oldCache = caches.remove(cacheName);
        }
        if (oldCache != null) {
            oldCache.stop();
        }

        return oldCache != null;
    }

    @Override
    public javax.transaction.UserTransaction getUserTransaction() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSupported(OptionalFeature optionalFeature) {
        return Caching.isSupported(optionalFeature);
    }

    @Override
    public void shutdown() {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        super.shutdown();
        ArrayList<Cache<?, ?>> cacheList;
        synchronized (caches) {
            cacheList = new ArrayList<Cache<?, ?>>(caches.values());
            caches.clear();
        }
        for (Cache<?, ?> cache : cacheList) {
            try {
                cache.stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error stopping cache: " + cache);
            }
        }
        status = Status.STOPPED;
    }

    @Override
    public <T> T unwrap(Class<T> cls) {
        if (cls.isAssignableFrom(this.getClass())) {
            return cls.cast(this);
        }
        throw new IllegalArgumentException();
    }

    private void addCacheInternal(Cache<?, ?> cache) throws CacheException {
        Cache oldCache;
        synchronized (caches) {
            oldCache = caches.put(cache.getName(), cache);
        }
        cache.start();
        if (oldCache != null) {
            oldCache.stop();
        }
    }

    private class CoherenceCacheBuilder<K, V> extends DelegatingCacheBuilder<K, V> {
        public CoherenceCacheBuilder(String cacheName) {
            super(new CoherenceCache.Builder<K, V>(cacheName, getName(), getClassLoader(), dccf));
        }

        @Override
        public Cache<K, V> build() {
            Cache<K, V> cache = super.build();
            addCacheInternal(cache);
            return cache;
        }
    }
}
