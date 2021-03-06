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

import com.tangosol.coherence.jsr107.processors.ConverterProcessor;
import com.tangosol.coherence.jsr107.processors.ProcessorFactory;
import com.tangosol.net.BackingMapManagerContext;
import com.tangosol.net.ConfigurableCacheFactory;
import com.tangosol.net.NamedCache;
import com.tangosol.net.PartitionedService;
import com.tangosol.net.partition.KeyPartitioningStrategy;
import com.tangosol.util.Binary;
import com.tangosol.util.Converter;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.InvocableMap;
import com.tangosol.util.LiteMap;
import com.tangosol.util.WrapperException;
import org.jsr107.ri.AbstractCache;
import org.jsr107.ri.DelegatingCacheMXBean;

import javax.cache.CacheConfiguration;
import javax.cache.CacheException;
import javax.cache.CacheLoader;
import javax.cache.CacheStatistics;
import javax.cache.CacheWriter;
import javax.cache.Status;
import javax.cache.event.CacheEntryListener;
import javax.cache.mbeans.CacheMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * @author ycosmado
 * @since 1.0
 */
public class CoherenceCache<K, V> extends AbstractCache<K, V> {
    private static final boolean TEMPORARY_YACHACK_TCK_FREEK = true;
    private final NamedCache namedCache;
    private volatile Status status;
    private final CoherenceCacheStatistics statistics;
    private final CacheMXBean mBean;
    private final ProcessorFactory<K, V> processorFactory;

    private CoherenceCache(NamedCache namedCache,
                           String cacheName,
                           String cacheManagerName,
                           ClassLoader classLoader,
                           CacheConfiguration<K, V> configuration,
                           CacheLoader<K, ? extends V> cacheLoader,
                           CacheWriter<? super K, ? super V> cacheWriter) {
        super(cacheName,
            cacheManagerName,
            classLoader,
            configuration,
            cacheLoader,
            cacheWriter);
        this.namedCache = namedCache;
        this.statistics = new CoherenceCacheStatistics(namedCache);
        mBean = new DelegatingCacheMXBean<K, V>(this);
        this.processorFactory = new ProcessorFactory<K, V>(namedCache);
        status = Status.UNINITIALISED;
    }

    @Override
    public V get(K key) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        try {
            long statisticsStart = getStatisticsStartMillis();
            V value = (V) fromBinary(invokeWithCacheLoader(key, processorFactory.getGetProcessor()));
            if (statisticsStart != 0 && getConfiguration().isStatisticsEnabled()) {
                if (value == null) {
                    statistics.registerMisses(1, statisticsStart);
                } else {
                    statistics.registerHits(1, statisticsStart);
                }
            }
            return value;
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    private long getStatisticsStartMillis() {
        return getConfiguration().isStatisticsEnabled() ? statistics.currentTimeMillis() : 0;
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) throws CacheException {
        checkStatusStarted();
        if (keys == null) {
            throw new NullPointerException();
        }
        if (keys.contains(null)) {
            throw new NullPointerException();
        }
        try {
            long statisticsStart = getStatisticsStartMillis();
            Map<K, V> map = fromBinary(invokeWithCacheLoader(keys, processorFactory.getGetProcessor()));
            if (statisticsStart != 0 && getConfiguration().isStatisticsEnabled()) {
                int hits = map.size();
                int misses = keys.size() - hits;
                if (hits > 0) {
                    statistics.registerHits(hits, statisticsStart);
                }
                if (misses > 0) {
                    statistics.registerMisses(misses, statisticsStart);
                }
            }
            return map;
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public boolean containsKey(K key) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        try {
            long statisticsStart = getStatisticsStartMillis();
            Boolean ret =  (Boolean) namedCache.invoke(key, processorFactory.getContainsKeyProcessor());
            if (statisticsStart != 0 && getConfiguration().isStatisticsEnabled()) {
                if (ret) {
                    statistics.registerHits(1, statisticsStart);
                } else {
                    statistics.registerMisses(1, statisticsStart);
                }
            }
            return ret;
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public Future<V> load(K key) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (getCacheLoader() == null) {
            return null;
        }
        FutureTask<V> task = new FutureTask<V>(new CoherenceCacheLoaderLoadCallable<K, V>(namedCache, getCacheLoader(), key));
        submit(task);
        return task;
    }

    @Override
    public Future<Map<K, ? extends V>> loadAll(Set<? extends K> keys) throws CacheException {
        checkStatusStarted();
        if (keys == null) {
            throw new NullPointerException();
        }
        if (keys.contains(null)) {
            throw new NullPointerException();
        }
        if (getCacheLoader() == null) {
            return null;
        }
        Callable<Map<K, ? extends V>> callable = new CoherenceCacheLoaderLoadAllCallable<K, V>(namedCache, getCacheLoader(), keys);
        FutureTask<Map<K, ? extends V>> task = new FutureTask<Map<K, ? extends V>>(callable);
        submit(task);
        return task;
    }

    @Override
    public CacheStatistics getStatistics() {
        checkStatusStarted();
        return getConfiguration().isStatisticsEnabled() ? statistics : null;
    }

    @Override
    public void put(K key, V value) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            namedCache.invoke(key, processorFactory.getPutProcessor(value));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public V getAndPut(K key, V value) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            return (V) fromBinary(namedCache.invoke(key, processorFactory.getGetAndPutProcessor(value)));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) throws CacheException {
        checkStatusStarted();
        if (map == null) {
            throw new NullPointerException();
        }
        if (map.containsKey(null)) {
            throw new NullPointerException();
        }
        if (map.containsValue(null)) {
            throw new NullPointerException();
        }
        //namedCache.putAll(map);
        putAllWithEntryProcessor(map);
    }

    private void putAllWithEntryProcessor(Map<? extends K, ? extends V> map) {
        BackingMapManagerContext context = namedCache.getCacheService().getBackingMapManager().getContext();
        Converter keyToInternalConverter = context.getKeyToInternalConverter();
        Converter valueToInternalConverter = context.getValueToInternalConverter();
        Converter keyFromInternalConverter = context.getKeyFromInternalConverter();

        HashMap<Integer, Map<Binary, Binary>> partitioned = new HashMap<Integer, Map<Binary, Binary>>();
        for (Map.Entry<? extends K,? extends V> entry : map.entrySet()) {
            Binary bKey = (Binary) keyToInternalConverter.convert(entry.getKey());
            Binary bValue = (Binary) valueToInternalConverter.convert(entry.getValue());
            int partition = context.getKeyPartition(bKey);
            Map<Binary, Binary> partitionMap = partitioned.get(partition);
            if (partitionMap == null) {
                partitionMap = new HashMap<Binary, Binary>();
                partitioned.put(partition, partitionMap);
            }
            partitionMap.put(bKey, bValue);
        }
        for (Map.Entry<Integer, Map<Binary, Binary>> entry : partitioned.entrySet()) {
            int partitionId = entry.getKey();
            Object partitionKey = keyFromInternalConverter.convert(entry.getValue().keySet().iterator().next()); // any key will do
            //TODO: I remember mention of some sort of "pseudo key" for a partition; if so how to get?
            namedCache.invoke(partitionKey, processorFactory.getPutAllProcessor(entry.getValue()));
        }
    }

    private void putAllWithEntryProcessor1(Map<? extends K, ? extends V> map) {
        PartitionedService ps = (PartitionedService) namedCache.getCacheService();
        KeyPartitioningStrategy keyPartitioningStrategy = ps.getKeyPartitioningStrategy();

        HashMap<Integer, Map<K, V>> partitioned = new HashMap<Integer, Map<K, V>>();
        for (Map.Entry<? extends K,? extends V> entry : map.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            int partition = keyPartitioningStrategy.getKeyPartition(key);
            Map<K, V> partitionMap = partitioned.get(partition);
            if (partitionMap == null) {
                partitionMap = new HashMap<K, V>();
                partitioned.put(partition, partitionMap);
            }
            partitionMap.put(key, value);
        }
        for (Map.Entry<Integer, Map<K, V>> entry : partitioned.entrySet()) {
            int partitionId = entry.getKey();
            Object partitionKey = entry.getValue().keySet().iterator().next(); // any key will do
            //TODO: I remember mention of some sort of "pseudo key" for a partition; if so how to get?
            namedCache.invoke(partitionKey, processorFactory.getPutAllProcessor1(entry.getValue()));
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            return (Boolean) namedCache.invoke(key, processorFactory.getPutIfAbsentProcessor(value));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public boolean remove(K key) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        try {
            return (Boolean) namedCache.invoke(key, processorFactory.getRemoveProcessor());
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public boolean remove(K key, V oldValue) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (oldValue == null) {
            throw new NullPointerException();
        }
        try {
            return (Boolean) namedCache.invoke(key, processorFactory.getRemove2Processor(oldValue));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public V getAndRemove(K key) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        try {
            return (V) fromBinary(namedCache.invoke(key, processorFactory.getGetAndRemoveProcessor()));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (oldValue == null) {
            throw new NullPointerException();
        }
        if (newValue == null) {
            throw new NullPointerException();
        }
        try {
            return (Boolean) namedCache.invoke(key, processorFactory.getReplace3Processor(oldValue, newValue));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public boolean replace(K key, V value) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            return (Boolean) namedCache.invoke(key, processorFactory.getReplace2Processor(value));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public V getAndReplace(K key, V value) throws CacheException {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (value == null) {
            throw new NullPointerException();
        }
        try {
            return (V) fromBinary(namedCache.invoke(key, processorFactory.getGetAndReplaceProcessor(value)));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) throws CacheException {
        checkStatusStarted();
        if (keys == null) {
            throw new NullPointerException();
        }
        if (keys.contains(null)) {
            throw new NullPointerException();
        }
        try {
            namedCache.invokeAll(keys, processorFactory.getRemoveProcessor());
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public void removeAll() throws CacheException {
        checkStatusStarted();
        namedCache.clear();
    }

    @Override
    public boolean registerCacheEntryListener(CacheEntryListener<? super K, ? super V> cacheEntryListener) {
        if (TEMPORARY_YACHACK_TCK_FREEK) {
            return false;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean unregisterCacheEntryListener(CacheEntryListener<?, ?> cacheEntryListener) {
        if (TEMPORARY_YACHACK_TCK_FREEK) {
            return false;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public Object invokeEntryProcessor(K key, EntryProcessor<K, V> entryProcessor) {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (entryProcessor == null) {
            throw new NullPointerException();
        }
        try {
            return namedCache.invoke(key, new ConverterProcessor(entryProcessor));
        } catch (WrapperException e) {
            throw thunkException(e);
        }
    }

    @Override
    public String getName() {
        return namedCache.getCacheName();
    }

    @Override
    public <T> T unwrap(Class<T> cls) {
        if (cls.isAssignableFrom(this.getClass())) {
            return cls.cast(this);
        }
        if (cls.isAssignableFrom(NamedCache.class)) {
            return cls.cast(namedCache);
        }
        throw new IllegalArgumentException();
    }

    @Override
    public void start() throws CacheException {
        status = Status.STARTED;
    }

    @Override
    public void stop() throws CacheException {
        super.stop();
        namedCache.clear();
        //TODO: this causes problem
        //namedCache.release();
        status = Status.STOPPED;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        checkStatusStarted();
        return new EntryIterator<K, V>(namedCache.entrySet().iterator());
    }

    @Override
    public CacheMXBean getMBean() {
        return mBean;
    }

    private void checkStatusStarted() {
        if (!Status.STARTED.equals(status)) {
            throw new IllegalStateException("The cache status is not STARTED");
        }
    }

    private RuntimeException thunkException(WrapperException e) {
        Throwable originalException = e.getOriginalException();
        if (originalException instanceof RuntimeException) {
            return (RuntimeException) originalException;
        } else if (originalException instanceof Error) {
            throw (Error) originalException;
        } else {
            return new CacheException(originalException);
        }
    }

    private V invokeWithCacheLoader(K key, InvocableMap.EntryProcessor processor) {
        CacheLoader<K, ? extends V> cacheLoader = getCacheLoader();
        Object ret = cacheLoader == null ?
            namedCache.invoke(key, processor) :
            namedCache.invoke(key, processorFactory.getCacheLoaderProcessor(processor, cacheLoader));
        return (V) ret;
    }

    private Map<K, Binary> invokeWithCacheLoader(Collection<? extends K> keys, InvocableMap.EntryProcessor processor) {
        CacheLoader<K, ? extends V> cacheLoader = getCacheLoader();
        Object ret = cacheLoader == null ?
            namedCache.invokeAll(keys, processor) :
            namedCache.invokeAll(keys, processorFactory.getCacheLoaderProcessor(processor, cacheLoader));
        return (Map<K, Binary>) ret;
    }

    private Object fromBinary(Object o) {
        if (o == null) {
            return null;
        } else {
            return ExternalizableHelper.fromBinary((Binary) o, getClassLoader());
        }
    }

    //TODO: does not work...
//    private Map<K, V> fromBinary(Map<Binary, Binary> binaryResult) {
//        if (binaryResult == null) {
//            return null;
//        }
//        ClassLoader classLoader = getClassLoader();
//        LiteMap result = new LiteMap();
//        for (Map.Entry<Binary, Binary> entry : binaryResult.entrySet()) {
//            K key = (K) ExternalizableHelper.fromBinary(entry.getKey(), classLoader);
//            V value = (V) ExternalizableHelper.fromBinary(entry.getValue(), classLoader);
//            result.put(key, value);
//        }
//        return result;
//    }

    private Map<K, V> fromBinary(Map<K, Binary> binaryResult) {
        if (binaryResult == null) {
            return null;
        }
        ClassLoader classLoader = getClassLoader();
        LiteMap result = new LiteMap();
        for (Map.Entry<K, Binary> entry : binaryResult.entrySet()) {
            K key = (K) entry.getKey();
            V value = (V) ExternalizableHelper.fromBinary(entry.getValue(), classLoader);
            result.put(key, value);
        }
        return result;
    }

    public static class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
        private final Iterator<Map.Entry<K, V>> mapIterator;

        public EntryIterator(Iterator<Map.Entry<K, V>> iterator) {
            this.mapIterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return mapIterator.hasNext();
        }

        @Override
        public Entry<K, V> next() {
            final Map.Entry<K, V> mapEntry = mapIterator.next();
            return new Entry<K, V>() {
                @Override
                public K getKey() {
                    return mapEntry.getKey();
                }
                @Override
                public V getValue() {
                    return mapEntry.getValue();
                }
            };
        }

        @Override
        public void remove() {
            mapIterator.remove();
        }
    }

    static class Builder<K, V> extends AbstractCache.Builder<K, V> {
        private final ConfigurableCacheFactory ccf;

        public Builder(String cacheName, String cacheManagerName,
                       ClassLoader classLoader, ConfigurableCacheFactory ccf) {
            this(cacheName, cacheManagerName, classLoader, new CoherenceCacheConfiguration.Builder(), ccf);
        }

        private Builder(String cacheName, String cacheManagerName,
                        ClassLoader classLoader,
                        CoherenceCacheConfiguration.Builder configurationBuilder,
                        ConfigurableCacheFactory ccf) {
            super(cacheName, cacheManagerName, classLoader, configurationBuilder);
            if (ccf == null) {
                throw new NullPointerException("ConfigurableCacheFactory");
            }
            this.ccf = ccf;
        }

        @Override
        public CoherenceCache<K, V> build() {
            CacheConfiguration configuration = createCacheConfiguration();
            NamedCache namedCache = ccf.ensureCache(cacheName, classLoader);
            return new CoherenceCache<K, V>(namedCache, cacheName, cacheManagerName, classLoader,
                    configuration, cacheLoader, cacheWriter);
        }

        /**
         * Set the cache loader.
         *
         * @param cacheLoader the CacheLoader
         * @return the builder
         */
        @Override
        public Builder<K, V> setCacheLoader(CacheLoader<K, ? extends V> cacheLoader) {
            if (cacheLoader == null) {
                throw new NullPointerException("cacheLoader");
            }
            this.cacheLoader = cacheLoader;
            return this;
        }

        @Override
        public Builder<K, V> registerCacheEntryListener(CacheEntryListener<K, V> listener) {
            throw new UnsupportedOperationException();
        }
    }

    private static class CoherenceCacheLoaderLoadCallable<K, V> implements Callable<V> {
        private final NamedCache cache;
        private final CacheLoader<K, ? extends V> cacheLoader;
        private final K key;

        public CoherenceCacheLoaderLoadCallable(NamedCache cache, CacheLoader<K, ? extends V> cacheLoader, K key) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.key = key;
        }

        @Override
        public V call() throws Exception {
            Entry<K, ? extends V> entry = cacheLoader.load(key);
            if (entry.getValue() == null) {
                throw new NullPointerException();
            }
            cache.put(entry.getKey(), entry.getValue());
            return entry.getValue();
        }
    }

    private static class CoherenceCacheLoaderLoadAllCallable<K, V> implements Callable<Map<K, ? extends V>> {
        private final NamedCache cache;
        private final CacheLoader<K, ? extends V> cacheLoader;
        private final Collection<? extends K> keys;

        CoherenceCacheLoaderLoadAllCallable(NamedCache cache, CacheLoader<K, ? extends V> cacheLoader, Collection<? extends K> keys) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.keys = keys;
        }

        @Override
        public Map<K, ? extends V> call() throws Exception {
            ArrayList<K> keysNotInStore = new ArrayList<K>();
            for (K key : keys) {
                if (!cache.containsKey(key)) {
                    keysNotInStore.add(key);
                }
            }
            Map<K, ? extends V> value = cacheLoader.loadAll(keysNotInStore);
            if (value.containsValue(null)) {
                throw new NullPointerException();
            }
            cache.putAll(value);
            return value;
        }
    }
}
