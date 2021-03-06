/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.internal.managers.swapspace.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.processors.offheap.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.offheap.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.swapspace.*;
import org.jetbrains.annotations.*;
import org.jsr166.*;

import javax.cache.*;
import java.lang.ref.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.cache.CacheMemoryMode.*;
import static org.apache.ignite.events.EventType.*;

/**
 * Handles all swap operations.
 */
public class GridCacheSwapManager extends GridCacheManagerAdapter {
    /** Swap manager. */
    private GridSwapSpaceManager swapMgr;

    /** */
    private String spaceName;

    /** Flag to indicate if manager is enabled. */
    private final boolean enabled;

    /** Flag to indicate if swap is enabled. */
    private boolean swapEnabled;

    /** Flag to indicate if offheap is enabled. */
    private boolean offheapEnabled;

    /** Swap listeners. */
    private final ConcurrentMap<Integer, Collection<GridCacheSwapListener>>
        swapLsnrs = new ConcurrentHashMap8<>();

    /** Swap listeners. */
    private final ConcurrentMap<Integer, Collection<GridCacheSwapListener>>
        offheapLsnrs = new ConcurrentHashMap8<>();

    /** Offheap. */
    private GridOffHeapProcessor offheap;

    /** Soft iterator queue. */
    private final ReferenceQueue<Iterator<Map.Entry>> itQ = new ReferenceQueue<>();

    /** Soft iterator set. */
    private final Collection<GridWeakIterator<Map.Entry>> itSet =
        new GridConcurrentHashSet<>();

    /**
     * @param enabled Flag to indicate if swap is enabled.
     */
    public GridCacheSwapManager(boolean enabled) {
        this.enabled = enabled;
    }

    /** {@inheritDoc} */
    @Override public void start0() throws IgniteCheckedException {
        spaceName = CU.swapSpaceName(cctx);

        swapMgr = cctx.gridSwap();
        offheap = cctx.offheap();

        swapEnabled = enabled && cctx.config().isSwapEnabled() && cctx.kernalContext().swap().enabled();
        offheapEnabled = enabled && cctx.config().getOffHeapMaxMemory() >= 0 &&
            (cctx.config().getMemoryMode() == ONHEAP_TIERED || cctx.config().getMemoryMode() == OFFHEAP_TIERED);

        if (offheapEnabled)
            initOffHeap();
    }

    /**
     * Initializes off-heap space.
     */
    private void initOffHeap() {
        // Register big data usage.
        long max = cctx.config().getOffHeapMaxMemory();

        long init = max > 0 ? max / 1024 : 8L * 1024L * 1024L;

        int parts = cctx.config().getAffinity().partitions();

        GridOffHeapEvictListener lsnr = !swapEnabled && !offheapEnabled ? null : new GridOffHeapEvictListener() {
            private volatile boolean firstEvictWarn;

            @Override public void onEvict(int part, int hash, byte[] kb, byte[] vb) {
                try {
                    if (!firstEvictWarn)
                        warnFirstEvict();

                    writeToSwap(part, cctx.toCacheKeyObject(kb), vb);
                }
                catch (IgniteCheckedException e) {
                    log.error("Failed to unmarshal off-heap entry [part=" + part + ", hash=" + hash + ']', e);
                }
            }

            private void warnFirstEvict() {
                synchronized (this) {
                    if (firstEvictWarn)
                        return;

                    firstEvictWarn = true;
                }

                U.warn(log, "Off-heap evictions started. You may wish to increase 'offHeapMaxMemory' in " +
                    "cache configuration [cache=" + cctx.name() +
                    ", offHeapMaxMemory=" + cctx.config().getOffHeapMaxMemory() + ']',
                    "Off-heap evictions started: " + cctx.name());
            }
        };

        offheap.create(spaceName, parts, init, max, lsnr);
    }

    /**
     * @return {@code True} if swap store is enabled.
     */
    public boolean swapEnabled() {
        return swapEnabled;
    }

    /**
     * @return {@code True} if off-heap cache is enabled.
     */
    public boolean offHeapEnabled() {
        return offheapEnabled;
    }

    /**
     * @return Swap size.
     * @throws IgniteCheckedException If failed.
     */
    public long swapSize() throws IgniteCheckedException {
        return enabled ? swapMgr.swapSize(spaceName) : -1;
    }

    /**
     * @param primary If {@code true} includes primary entries.
     * @param backup If {@code true} includes backup entries.
     * @param topVer Topology version.
     * @return Number of swap entries.
     * @throws IgniteCheckedException If failed.
     */
    public int swapEntriesCount(boolean primary, boolean backup, AffinityTopologyVersion topVer) throws IgniteCheckedException {
        assert primary || backup;

        if (!swapEnabled)
            return 0;

        if (!(primary && backup)) {
            Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
                cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

            return (int)swapMgr.swapKeys(spaceName, parts);
        }
        else
            return (int)swapMgr.swapKeys(spaceName);
    }

    /**
     * @param primary If {@code true} includes primary entries.
     * @param backup If {@code true} includes backup entries.
     * @param topVer Topology version.
     * @return Number of offheap entries.
     * @throws IgniteCheckedException If failed.
     */
    public int offheapEntriesCount(boolean primary, boolean backup, AffinityTopologyVersion topVer) throws IgniteCheckedException {
        assert primary || backup;

        if (!offheapEnabled)
            return 0;

        if (!(primary && backup)) {
            Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
                cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

            return (int)offheap.entriesCount(spaceName, parts);
        }
        else
            return (int)offheap.entriesCount(spaceName);
    }

    /**
     * Gets number of swap entries (keys).
     *
     * @return Swap keys count.
     * @throws IgniteCheckedException If failed.
     */
    public long swapKeys() throws IgniteCheckedException {
        return enabled ? swapMgr.swapKeys(spaceName) : -1;
    }

    /**
     * @param part Partition.
     * @param key Cache key.
     * @param e Entry.
     * @throws IgniteCheckedException If failed.
     */
    private void onUnswapped(int part, KeyCacheObject key, GridCacheSwapEntry e) throws IgniteCheckedException {
        onEntryUnswapped(swapLsnrs, part, key, e);
    }

    /**
     * @param part Partition.
     * @param key Cache key.
     * @param e Entry.
     * @throws IgniteCheckedException If failed.
     */
    private void onOffHeaped(int part, KeyCacheObject key, GridCacheSwapEntry e) throws IgniteCheckedException {
        onEntryUnswapped(offheapLsnrs, part, key, e);
    }

    /**
     * @param map Listeners.
     * @param part Partition.
     * @param key Cache key.
     * @param e Entry.
     * @throws IgniteCheckedException If failed.
     */
    private void onEntryUnswapped(ConcurrentMap<Integer, Collection<GridCacheSwapListener>> map,
        int part, KeyCacheObject key, GridCacheSwapEntry e) throws IgniteCheckedException {
        Collection<GridCacheSwapListener> lsnrs = map.get(part);

        if (lsnrs == null) {
            if (log.isDebugEnabled())
                log.debug("Skipping unswapped notification [key=" + key + ", part=" + part + ']');

            return;
        }

        for (GridCacheSwapListener lsnr : lsnrs)
            lsnr.onEntryUnswapped(part, key, e);
    }

    /**
     * @param part Partition.
     * @param lsnr Listener.
     */
    public void addSwapListener(int part, GridCacheSwapListener lsnr) {
        addListener(part, swapLsnrs, lsnr);
    }

    /**
     * @param part Partition.
     * @param lsnr Listener.
     */
    public void removeSwapListener(int part, GridCacheSwapListener lsnr) {
        removeListener(part, swapLsnrs, lsnr);
    }

    /**
     * @param part Partition.
     * @param lsnr Listener.
     */
    public void addOffHeapListener(int part, GridCacheSwapListener lsnr) {
        addListener(part, offheapLsnrs, lsnr);
    }

    /**
     * @param part Partition.
     * @param lsnr Listener.
     */
    public void removeOffHeapListener(int part, GridCacheSwapListener lsnr) {
        removeListener(part, offheapLsnrs, lsnr);
    }

    /**
     * @param part Partition.
     * @param map Listeners.
     * @param lsnr Listener.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private void addListener(int part, ConcurrentMap<Integer, Collection<GridCacheSwapListener>> map,
        GridCacheSwapListener lsnr) {
        Collection<GridCacheSwapListener> lsnrs = map.get(part);

        while (true) {
            if (lsnrs != null) {
                synchronized (lsnrs) {
                    if (!lsnrs.isEmpty()) {
                        lsnrs.add(lsnr);

                        break;
                    }
                }

                lsnrs = swapLsnrs.remove(part, lsnrs) ? null : swapLsnrs.get(part);
            }
            else {
                lsnrs = new GridConcurrentHashSet<GridCacheSwapListener>() {
                    @Override public boolean equals(Object o) {
                        return o == this;
                    }
                };

                lsnrs.add(lsnr);

                Collection<GridCacheSwapListener> old = swapLsnrs.putIfAbsent(part, lsnrs);

                if (old == null)
                    break;
                else
                    lsnrs = old;
            }
        }
    }

    /**
     * @param part Partition.
     * @param map Listeners.
     * @param lsnr Listener.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private void removeListener(int part, ConcurrentMap<Integer, Collection<GridCacheSwapListener>> map,
        GridCacheSwapListener lsnr) {
        Collection<GridCacheSwapListener> lsnrs = map.get(part);

        if (lsnrs != null) {
            boolean empty;

            synchronized (lsnrs) {
                lsnrs.remove(lsnr);

                empty = lsnrs.isEmpty();
            }

            if (empty)
                map.remove(part, lsnrs);
        }
    }

    /**
     * Checks iterator queue.
     */
    @SuppressWarnings("RedundantCast")
    private void checkIteratorQueue() {
        GridWeakIterator<Map.Entry> it;

        do {
            // NOTE: don't remove redundant cast - otherwise build fails.
            it = (GridWeakIterator<Map.Entry>)(Reference<Iterator<Map.Entry>>)itQ.poll();

            try {
                if (it != null)
                    it.close();
            }
            catch (IgniteCheckedException e) {
                log.error("Failed to close iterator.", e);
            }
            finally {
                if (it != null)
                    itSet.remove(it);
            }
        }
        while (it != null);
    }

    /**
     * Recreates raw swap entry (that just has been received from swap storage).
     *
     * @param e Swap entry to reconstitute.
     * @return Reconstituted swap entry or {@code null} if entry is obsolete.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private <X extends GridCacheSwapEntry> X swapEntry(X e) throws IgniteCheckedException
    {
        assert e != null;

        checkIteratorQueue();

        CacheObject val = cctx.unswapCacheObject(e.type(), e.valueBytes(), e.valueClassLoaderId());

        if (val == null)
            return null;

        e.value(val);

        return e;
    }

    /**
     * @param key Key to check.
     * @return {@code True} if key is contained.
     * @throws IgniteCheckedException If failed.
     */
    public boolean containsKey(KeyCacheObject key) throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return false;

        checkIteratorQueue();

        int part = cctx.affinity().partition(key);

        // First check off-heap store.
        if (offheapEnabled)
            if (offheap.contains(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext())))
                return true;

        if (swapEnabled) {
            assert key != null;

            byte[] valBytes = swapMgr.read(spaceName,
                new SwapKey(key.value(cctx.cacheObjectContext(), false), part, key.valueBytes(cctx.cacheObjectContext())),
                cctx.deploy().globalLoader());

            return valBytes != null;
        }

        return false;
    }

    /**
     * @param key Key to read.
     * @param keyBytes Key bytes.
     * @param part Key partition.
     * @param entryLocked {@code True} if cache entry is locked.
     * @param readOffheap Read offheap flag.
     * @param readSwap Read swap flag.
     * @return Value from swap or {@code null}.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private GridCacheSwapEntry read(KeyCacheObject key,
        byte[] keyBytes,
        int part,
        boolean entryLocked,
        boolean readOffheap,
        boolean readSwap)
        throws IgniteCheckedException
    {
        assert readOffheap || readSwap;

        if (!offheapEnabled && !swapEnabled)
            return null;

        checkIteratorQueue();

        KeySwapListener lsnr = null;

        try {
            if (offheapEnabled && swapEnabled && !entryLocked) {
                lsnr = new KeySwapListener(key);

                addSwapListener(part, lsnr);
            }

            // First check off-heap store.
            if (readOffheap && offheapEnabled) {
                byte[] bytes = offheap.get(spaceName, part, key, keyBytes);

                if (bytes != null)
                    return swapEntry(unmarshalSwapEntry(bytes));
            }

            if (!swapEnabled || !readSwap)
                return null;

            assert key != null;

            byte[] bytes = swapMgr.read(spaceName,
                new SwapKey(key.value(cctx.cacheObjectContext(), false), part, keyBytes),
                cctx.deploy().globalLoader());

            if (bytes == null && lsnr != null)
                return lsnr.entry;

            return bytes != null ? swapEntry(unmarshalSwapEntry(bytes)) : null;
        }
        finally {
            if (lsnr != null)
                removeSwapListener(part, lsnr);
        }
    }

    /**
     * @param key Key to remove.
     * @return Value from swap or {@code null}.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable GridCacheSwapEntry readAndRemove(final KeyCacheObject key)
        throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return null;

        checkIteratorQueue();

        final int part = cctx.affinity().partition(key);

        // First try removing from offheap.
        if (offheapEnabled) {
            byte[] entryBytes = offheap.remove(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));

            if (entryBytes != null) {
                GridCacheSwapEntry entry = swapEntry(unmarshalSwapEntry(entryBytes));

                if (entry == null)
                    return null;

                // Always fire this event, since preloading depends on it.
                onOffHeaped(part, key, entry);

                if (cctx.events().isRecordable(EVT_CACHE_OBJECT_FROM_OFFHEAP))
                    cctx.events().addEvent(
                        part,
                        key,
                        cctx.nodeId(),
                        (IgniteUuid)null,
                        null,
                        EVT_CACHE_OBJECT_FROM_OFFHEAP,
                        null,
                        false,
                        null,
                        true,
                        null,
                        null,
                        null);

                GridCacheQueryManager qryMgr = cctx.queries();

                if (qryMgr != null)
                    qryMgr.onUnswap(key, entry.value());

                return entry;
            }
        }

        return readAndRemoveSwap(key, part);
    }

    /**
     * @param key Key.
     * @param part Partition.
     * @return Value from swap or {@code null}.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private GridCacheSwapEntry readAndRemoveSwap(final KeyCacheObject key,
        final int part)
        throws IgniteCheckedException {
        if (!swapEnabled)
            return null;

        final GridTuple<GridCacheSwapEntry> t = F.t1();
        final GridTuple<IgniteCheckedException> err = F.t1();

        SwapKey swapKey = new SwapKey(key.value(cctx.cacheObjectContext(), false),
            part,
            key.valueBytes(cctx.cacheObjectContext()));

        swapMgr.remove(spaceName, swapKey, new CI1<byte[]>() {
            @Override public void apply(byte[] rmv) {
                if (rmv != null) {
                    try {
                        GridCacheSwapEntry entry = swapEntry(unmarshalSwapEntry(rmv));

                        if (entry == null)
                            return;

                        t.set(entry);

                        CacheObject v = entry.value();
                        byte[] valBytes = entry.valueBytes();

                        // Event notification.
                        if (cctx.events().isRecordable(EVT_CACHE_OBJECT_UNSWAPPED)) {
                            cctx.events().addEvent(part,
                                key,
                                cctx.nodeId(),
                                (IgniteUuid)null,
                                null,
                                EVT_CACHE_OBJECT_UNSWAPPED,
                                null,
                                false,
                                v,
                                true,
                                null,
                                null,
                                null);
                        }

                        // Always fire this event, since preloading depends on it.
                        onUnswapped(part, key, entry);

                        GridCacheQueryManager qryMgr = cctx.queries();

                        if (qryMgr != null)
                            qryMgr.onUnswap(key, v);
                    }
                    catch (IgniteCheckedException e) {
                        err.set(e);
                    }
                }
            }
        }, cctx.deploy().globalLoader());

        if (err.get() != null)
            throw err.get();

        return t.get();
    }

    /**
     * @param entry Entry to read.
     * @param locked {@code True} if cache entry is locked.
     * @param readOffheap Read offheap flag.
     * @param readSwap Read swap flag.
     * @return Read value.
     * @throws IgniteCheckedException If read failed.
     */
    @Nullable GridCacheSwapEntry read(GridCacheEntryEx entry,
        boolean locked,
        boolean readOffheap,
        boolean readSwap)
        throws IgniteCheckedException
    {
        if (!offheapEnabled && !swapEnabled)
            return null;

        return read(entry.key(),
            entry.key().valueBytes(cctx.cacheObjectContext()),
            entry.partition(),
            locked,
            readOffheap,
            readSwap);
    }

    /**
     * @param entry Entry to read.
     * @return Read value address.
     * @throws IgniteCheckedException If read failed.
     */
    @Nullable GridCacheSwapEntry readOffheapPointer(GridCacheEntryEx entry) throws IgniteCheckedException {
        if (!offheapEnabled)
            return null;

        KeyCacheObject key = entry.key();

        int part = cctx.affinity().partition(key);

        IgniteBiTuple<Long, Integer> ptr =
            offheap.valuePointer(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));

        if (ptr != null) {
            assert ptr.get1() != null;
            assert ptr.get2() != null;

            return new GridCacheOffheapSwapEntry(ptr.get1(), ptr.get2());
        }

        return readAndRemoveSwap(key, part);
    }

    /**
     * @param key Key to read swap entry for.
     * @param readOffheap Read offheap flag.
     * @param readSwap Read swap flag.
     * @return Read value.
     * @throws IgniteCheckedException If read failed.
     */
    @Nullable public GridCacheSwapEntry read(KeyCacheObject key,
        boolean readOffheap,
        boolean readSwap)
        throws IgniteCheckedException
    {
        if (!offheapEnabled && !swapEnabled)
            return null;

        int part = cctx.affinity().partition(key);

        return read(key, key.valueBytes(cctx.cacheObjectContext()), part, false, readOffheap, readSwap);
    }

    /**
     * @param entry Entry to read.
     * @return Read value.
     * @throws IgniteCheckedException If read failed.
     */
    @Nullable GridCacheSwapEntry readAndRemove(GridCacheEntryEx entry) throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return null;

        return readAndRemove(entry.key());
    }

    /**
     * @param keys Collection of keys to remove from swap.
     * @return Collection of swap entries.
     * @throws IgniteCheckedException If failed,
     */
    public Collection<GridCacheBatchSwapEntry> readAndRemove(Collection<? extends KeyCacheObject> keys)
        throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return Collections.emptyList();

        checkIteratorQueue();

        final GridCacheQueryManager qryMgr = cctx.queries();

        Collection<SwapKey> unprocessedKeys = null;
        final Collection<GridCacheBatchSwapEntry> res = new ArrayList<>(keys.size());

        // First try removing from offheap.
        if (offheapEnabled) {
            for (KeyCacheObject key : keys) {
                int part = cctx.affinity().partition(key);

                byte[] entryBytes =
                    offheap.remove(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));

                if (entryBytes != null) {
                    GridCacheSwapEntry entry = swapEntry(unmarshalSwapEntry(entryBytes));

                    if (entry != null) {
                        // Always fire this event, since preloading depends on it.
                        onOffHeaped(part, key, entry);

                        if (cctx.events().isRecordable(EVT_CACHE_OBJECT_FROM_OFFHEAP))
                            cctx.events().addEvent(part, key, cctx.nodeId(), (IgniteUuid)null, null,
                                EVT_CACHE_OBJECT_FROM_OFFHEAP, null, false, null, true, null, null, null);

                        if (qryMgr != null)
                            qryMgr.onUnswap(key, entry.value());

                        GridCacheBatchSwapEntry unswapped = new GridCacheBatchSwapEntry(key,
                            part,
                            ByteBuffer.wrap(entry.valueBytes()),
                            entry.type(),
                            entry.version(), entry.ttl(),
                            entry.expireTime(),
                            entry.keyClassLoaderId(),
                            entry.valueClassLoaderId());

                        unswapped.value(entry.value());

                        res.add(unswapped);

                        continue;
                    }
                }

                if (swapEnabled) {
                    if (unprocessedKeys == null)
                        unprocessedKeys = new ArrayList<>(keys.size());

                    SwapKey swapKey = new SwapKey(key.value(cctx.cacheObjectContext(), false),
                        cctx.affinity().partition(key),
                        key.valueBytes(cctx.cacheObjectContext()));

                    unprocessedKeys.add(swapKey);
                }
            }

            if (unprocessedKeys == null)
                return res;
        }
        else {
            unprocessedKeys = new ArrayList<>(keys.size());

            for (KeyCacheObject key : keys) {
                SwapKey swapKey = new SwapKey(key.value(cctx.cacheObjectContext(), false),
                    cctx.affinity().partition(key),
                    key.valueBytes(cctx.cacheObjectContext()));

                unprocessedKeys.add(swapKey);
            }
        }

        assert swapEnabled;
        assert unprocessedKeys != null;

        // Swap is enabled.
        final GridTuple<IgniteCheckedException> err = F.t1();

        swapMgr.removeAll(spaceName,
            unprocessedKeys,
            new IgniteBiInClosure<SwapKey, byte[]>() {
                @Override public void apply(SwapKey swapKey, byte[] rmv) {
                    if (rmv != null) {
                        try {
                            GridCacheSwapEntry entry = swapEntry(unmarshalSwapEntry(rmv));

                            if (entry == null)
                                return;

                            KeyCacheObject key = cctx.toCacheKeyObject(swapKey.keyBytes());

                            GridCacheBatchSwapEntry unswapped = new GridCacheBatchSwapEntry(key,
                                swapKey.partition(),
                                ByteBuffer.wrap(entry.valueBytes()),
                                entry.type(),
                                entry.version(),
                                entry.ttl(),
                                entry.expireTime(),
                                entry.keyClassLoaderId(),
                                entry.valueClassLoaderId());

                            unswapped.value(entry.value());

                            res.add(unswapped);

                            // Event notification.
                            if (cctx.events().isRecordable(EVT_CACHE_OBJECT_UNSWAPPED)) {
                                cctx.events().addEvent(swapKey.partition(),
                                    key,
                                    cctx.nodeId(),
                                    (IgniteUuid)null,
                                    null,
                                    EVT_CACHE_OBJECT_UNSWAPPED,
                                    null,
                                    false,
                                    entry.value(),
                                    true,
                                    null,
                                    null,
                                    null);
                            }

                            // Always fire this event, since preloading depends on it.
                            onUnswapped(swapKey.partition(), key, entry);

                            if (qryMgr != null)
                                qryMgr.onUnswap(key, entry.value());
                        }
                        catch (IgniteCheckedException e) {
                            err.set(e);
                        }
                    }
                }
            },
            cctx.deploy().globalLoader());

        if (err.get() != null)
            throw err.get();

        return res;
    }

    /**
     * @param key Key to remove.
     * @return {@code True} If succeeded.
     * @throws IgniteCheckedException If failed.
     */
    boolean removeOffheap(final KeyCacheObject key) throws IgniteCheckedException {
        assert offheapEnabled;

        checkIteratorQueue();

        int part = cctx.affinity().partition(key);

        return offheap.removex(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));
    }

    /**
     * @return {@code True} if offheap eviction is enabled.
     */
    boolean offheapEvictionEnabled() {
        return offheapEnabled && cctx.config().getOffHeapMaxMemory() > 0;
    }

    /**
     * Enables eviction for offheap entry after {@link #readOffheapPointer} was called.
     *
     * @param key Key.
     * @throws IgniteCheckedException If failed.
     */
    void enableOffheapEviction(final KeyCacheObject key) throws IgniteCheckedException {
        if (!offheapEnabled)
            return;

        checkIteratorQueue();

        int part = cctx.affinity().partition(key);

        offheap.enableEviction(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));
    }

    /**
     * @param key Key to remove.
     * @throws IgniteCheckedException If failed.
     */
    public void remove(final KeyCacheObject key) throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return;

        checkIteratorQueue();

        final GridCacheQueryManager qryMgr = cctx.queries();

        CI1<byte[]> c = qryMgr == null ? null : new CI1<byte[]>() {
            @Override public void apply(byte[] rmv) {
                if (rmv == null)
                    return;

                try {
                    GridCacheSwapEntry entry = swapEntry(unmarshalSwapEntry(rmv));

                    if (entry == null)
                        return;

                    qryMgr.onUnswap(key, entry.value());
                }
                catch (IgniteCheckedException e) {
                    throw new IgniteException(e);
                }
            }
        };

        int part = cctx.affinity().partition(key);

        // First try offheap.
        if (offheapEnabled) {
            byte[] val = offheap.remove(spaceName,
                part,
                key.value(cctx.cacheObjectContext(), false),
                key.valueBytes(cctx.cacheObjectContext()));

            if (val != null) {
                if (c != null)
                    c.apply(val); // Probably we should read value and apply closure before removing...

                return;
            }
        }

        if (swapEnabled) {
            SwapKey swapKey = new SwapKey(key.value(cctx.cacheObjectContext(), false),
                part,
                key.valueBytes(cctx.cacheObjectContext()));

            swapMgr.remove(spaceName,
                swapKey,
                c,
                cctx.deploy().globalLoader());
        }
    }

    /**
     * Writes a versioned value to swap.
     *
     * @param key Key.
     * @param val Value.
     * @param type Value type.
     * @param ver Version.
     * @param ttl Entry time to live.
     * @param expireTime Swap entry expiration time.
     * @param keyClsLdrId Class loader ID for entry key.
     * @param valClsLdrId Class loader ID for entry value.
     * @throws IgniteCheckedException If failed.
     */
    void write(KeyCacheObject key,
        ByteBuffer val,
        byte type,
        GridCacheVersion ver,
        long ttl,
        long expireTime,
        @Nullable IgniteUuid keyClsLdrId,
        @Nullable IgniteUuid valClsLdrId)
        throws IgniteCheckedException {
        if (!offheapEnabled && !swapEnabled)
            return;

        checkIteratorQueue();

        int part = cctx.affinity().partition(key);

        GridCacheSwapEntryImpl entry = new GridCacheSwapEntryImpl(val,
            type,
            ver,
            ttl,
            expireTime,
            keyClsLdrId,
            valClsLdrId);

        if (offheapEnabled) {
            offheap.put(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()), entry.marshal());

            if (cctx.events().isRecordable(EVT_CACHE_OBJECT_TO_OFFHEAP))
                cctx.events().addEvent(part, key, cctx.nodeId(), (IgniteUuid)null, null,
                    EVT_CACHE_OBJECT_TO_OFFHEAP, null, false, null, true, null, null, null);
        }
        else if (swapEnabled)
            writeToSwap(part, key, entry.marshal());

        GridCacheQueryManager qryMgr = cctx.queries();

        if (qryMgr != null)
            qryMgr.onSwap(key);
    }

    /**
     * Performs batch write of swapped entries.
     *
     * @param swapped Collection of swapped entries.
     * @throws IgniteCheckedException If failed.
     */
    void writeAll(Iterable<GridCacheBatchSwapEntry> swapped) throws IgniteCheckedException {
        assert offheapEnabled || swapEnabled;

        checkIteratorQueue();

        GridCacheQueryManager qryMgr = cctx.queries();

        if (offheapEnabled) {
            for (GridCacheBatchSwapEntry swapEntry : swapped) {
                offheap.put(spaceName,
                    swapEntry.partition(),
                    swapEntry.key(),
                    swapEntry.key().valueBytes(cctx.cacheObjectContext()),
                    swapEntry.marshal());

                if (cctx.events().isRecordable(EVT_CACHE_OBJECT_TO_OFFHEAP))
                    cctx.events().addEvent(swapEntry.partition(), swapEntry.key(), cctx.nodeId(),
                        (IgniteUuid)null, null, EVT_CACHE_OBJECT_TO_OFFHEAP, null, false, null, true, null, null, null);

                if (qryMgr != null)
                    qryMgr.onSwap(swapEntry.key());
            }
        }
        else {
            Map<SwapKey, byte[]> batch = new LinkedHashMap<>();

            for (GridCacheBatchSwapEntry entry : swapped) {
                SwapKey swapKey = new SwapKey(entry.key().value(cctx.cacheObjectContext(), false),
                    entry.partition(),
                    entry.key().valueBytes(cctx.cacheObjectContext()));

                batch.put(swapKey, entry.marshal());
            }

            swapMgr.writeAll(spaceName, batch, cctx.deploy().globalLoader());

            if (cctx.events().isRecordable(EVT_CACHE_OBJECT_SWAPPED)) {
                for (GridCacheBatchSwapEntry batchSwapEntry : swapped) {
                    cctx.events().addEvent(batchSwapEntry.partition(), batchSwapEntry.key(), cctx.nodeId(),
                        (IgniteUuid)null, null, EVT_CACHE_OBJECT_SWAPPED, null, false, null, true, null, null, null);

                    if (qryMgr != null)
                        qryMgr.onSwap(batchSwapEntry.key());
                }
            }
        }
    }

    /**
     * Writes given bytes to swap.
     *
     * @param part Partition.
     * @param key Key. If {@code null} then it will be deserialized from {@code keyBytes}.
     * @param entry Entry bytes.
     * @throws IgniteCheckedException If failed.
     */
    private void writeToSwap(int part,
        KeyCacheObject key,
        byte[] entry)
        throws IgniteCheckedException
    {
        checkIteratorQueue();

        swapMgr.write(spaceName,
            new SwapKey(key.value(cctx.cacheObjectContext(), false), part, key.valueBytes(cctx.cacheObjectContext())),
            entry,
            cctx.deploy().globalLoader());

        if (cctx.events().isRecordable(EVT_CACHE_OBJECT_SWAPPED))
            cctx.events().addEvent(part, key, cctx.nodeId(), (IgniteUuid) null, null,
                EVT_CACHE_OBJECT_SWAPPED, null, false, null, true, null, null, null);
    }

    /**
     * Clears off-heap.
     */
    public void clearOffHeap() {
        if (offheapEnabled)
            initOffHeap();
    }

    /**
     * Clears swap.
     *
     * @throws IgniteCheckedException If failed.
     */
    public void clearSwap() throws IgniteCheckedException {
        if (swapEnabled)
            swapMgr.clear(spaceName);
    }

    /**
     * Gets offheap and swap iterator over partition.
     *
     * @param part Partition to iterate over.
     * @return Iterator over partition.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> iterator(
        final int part)
        throws IgniteCheckedException {
        if (!swapEnabled() && !offHeapEnabled())
            return null;

        checkIteratorQueue();

        if (offHeapEnabled() && !swapEnabled())
            return offHeapIterator(part);

        if (swapEnabled() && !offHeapEnabled())
            return swapIterator(part);

        // Both, swap and off-heap are enabled.
        return new GridCloseableIteratorAdapter<Map.Entry<byte[], GridCacheSwapEntry>>() {
            private GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> it;

            private boolean offheap = true;

            private boolean done;

            {
                it = offHeapIterator(part);

                advance();
            }

            private void advance() throws IgniteCheckedException {
                if (it.hasNext())
                    return;

                it.close();

                if (offheap) {
                    offheap = false;

                    it = swapIterator(part);

                    assert it != null;

                    if (!it.hasNext()) {
                        it.close();

                        done = true;
                    }
                }
                else
                    done = true;
            }

            @Override protected Map.Entry<byte[], GridCacheSwapEntry> onNext() throws IgniteCheckedException {
                if (done)
                    throw new NoSuchElementException();

                Map.Entry<byte[], GridCacheSwapEntry> e = it.next();

                advance();

                return e;
            }

            @Override protected boolean onHasNext() {
                return !done;
            }

            @Override protected void onClose() throws IgniteCheckedException {
                if (it != null)
                    it.close();
            }
        };
    }

    /**
     * Gets offheap and swap iterator over partition.
     *
     * @return Iterator over partition.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public GridCloseableIterator<Map.Entry<byte[], byte[]>> rawIterator()
        throws IgniteCheckedException {
        if (!swapEnabled() && !offHeapEnabled())
            return new GridEmptyCloseableIterator<>();

        checkIteratorQueue();

        if (offHeapEnabled() && !swapEnabled())
            return rawOffHeapIterator();

        if (swapEnabled() && !offHeapEnabled())
            return rawSwapIterator();

        // Both, swap and off-heap are enabled.
        return new GridCloseableIteratorAdapter<Map.Entry<byte[], byte[]>>() {
            private GridCloseableIterator<Map.Entry<byte[], byte[]>> it;

            private boolean offheapFlag = true;

            private boolean done;

            private Map.Entry<byte[], byte[]> cur;

            {
                it = rawOffHeapIterator();

                advance();
            }

            private void advance() throws IgniteCheckedException {
                if (it.hasNext())
                    return;

                it.close();

                if (offheapFlag) {
                    offheapFlag = false;

                    it = rawSwapIterator();

                    if (!it.hasNext()) {
                        it.close();

                        done = true;
                    }
                }
                else
                    done = true;
            }

            @Override protected Map.Entry<byte[], byte[]> onNext() throws IgniteCheckedException {
                if (done)
                    throw new NoSuchElementException();

                cur = it.next();

                advance();

                return cur;
            }

            @Override protected boolean onHasNext() {
                return !done;
            }

            @Override protected void onRemove() throws IgniteCheckedException {
                if (offheapFlag) {
                    KeyCacheObject key = cctx.toCacheKeyObject(cur.getKey());

                    int part = cctx.affinity().partition(key);

                    offheap.removex(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));
                }
                else
                    it.removeX();
            }

            @Override protected void onClose() throws IgniteCheckedException {
                if (it != null)
                    it.close();
            }
        };
    }

    /**
     * @return Lazy swap iterator.
     * @throws IgniteCheckedException If failed.
     */
    public <K, V> Iterator<Map.Entry<K, V>> lazySwapIterator() throws IgniteCheckedException {
        if (!swapEnabled)
            return new GridEmptyIterator<>();

        return lazyIterator(cctx.gridSwap().rawIterator(spaceName));
    }

    /**
     * @return Iterator over off-heap keys.
     */
    public Iterator<KeyCacheObject> offHeapKeyIterator(boolean primary, boolean backup, AffinityTopologyVersion topVer) {
        assert primary || backup;

        if (!offheapEnabled)
            return F.emptyIterator();

        if (primary && backup)
            return keyIterator(offheap.iterator(spaceName));

        Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
            cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

        return new PartitionsKeyIterator(parts) {
            @Override protected Iterator<KeyCacheObject> partitionIterator(int part)
                throws IgniteCheckedException
            {
                return keyIterator(offheap.iterator(spaceName, part));
            }
        };
    }

    /**
     * @return Iterator over off-heap keys.
     */
    public Iterator<KeyCacheObject> swapKeyIterator(boolean primary, boolean backup, AffinityTopologyVersion topVer)
        throws IgniteCheckedException {
        assert primary || backup;

        if (!swapEnabled)
            return F.emptyIterator();

        if (primary && backup)
            return keyIterator(cctx.gridSwap().rawIterator(spaceName));

        Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
            cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

        return new PartitionsKeyIterator(parts) {
            @Override protected Iterator<KeyCacheObject> partitionIterator(int part)
                throws IgniteCheckedException
            {
                return keyIterator(swapMgr.rawIterator(spaceName, part));
            }
        };
    }

    /**
     * @return Lazy off-heap iterator.
     */
    public <K, V> Iterator<Map.Entry<K, V>> lazyOffHeapIterator() {
        if (!offheapEnabled)
            return new GridEmptyCloseableIterator<>();

        return lazyIterator(offheap.iterator(spaceName));
    }

    /**
     * Gets number of elements in off-heap
     *
     * @return Number of elements or {@code 0} if off-heap is disabled.
     */
    public long offHeapEntriesCount() {
        return offheapEnabled ? offheap.entriesCount(spaceName) : 0;
    }

    /**
     * Gets memory size allocated in off-heap.
     *
     * @return Allocated memory size or {@code 0} if off-heap is disabled.
     */
    public long offHeapAllocatedSize() {
        return offheapEnabled ? offheap.allocatedSize(spaceName) : 0;
    }

    /**
     * Gets lazy iterator for which key and value are lazily deserialized.
     *
     * @param it Closeable iterator.
     * @return Lazy iterator.
     */
    private <K, V> Iterator<Map.Entry<K, V>> lazyIterator(
        final GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> it) {
        if (it == null)
            return new GridEmptyIterator<>();

        checkIteratorQueue();

        // Weak reference will hold hard reference to this iterator, so it can properly be closed.
        final GridCloseableIteratorAdapter<Map.Entry<K, V>> iter = new GridCloseableIteratorAdapter<Map.Entry<K, V>>() {
            private Map.Entry<K, V> cur;

            @Override protected Map.Entry<K, V> onNext() {
                final Map.Entry<byte[], byte[]> cur0 = it.next();

                cur = new Map.Entry<K, V>() {
                    @Override public K getKey() {
                        try {
                            KeyCacheObject key = cctx.toCacheKeyObject(cur0.getKey());

                            return key.value(cctx.cacheObjectContext(), false);
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteException(e);
                        }
                    }

                    @Override public V getValue() {
                        try {
                            GridCacheSwapEntry e = unmarshalSwapEntry(cur0.getValue());

                            swapEntry(e);

                            return e.value().value(cctx.cacheObjectContext(), false);
                        }
                        catch (IgniteCheckedException ex) {
                            throw new IgniteException(ex);
                        }
                    }

                    @Override public V setValue(V val) {
                        throw new UnsupportedOperationException();
                    }
                };

                return cur;
            }

            @Override protected boolean onHasNext() {
                return it.hasNext();
            }

            @Override protected void onRemove() throws IgniteCheckedException {
                if (cur == null)
                    throw new IllegalStateException("Method next() has not yet been called, or the remove() method " +
                        "has already been called after the last call to the next() method.");

                try {
                    if (cctx.isDht())
                        cctx.dht().near().remove(cur.getKey());
                    else
                        cctx.cache().remove(cur.getKey());
                }
                finally {
                    cur = null;
                }
            }

            @Override protected void onClose() throws IgniteCheckedException {
                it.close();
            }
        };

        // Don't hold hard reference to this iterator - only weak one.
        Iterator<Map.Entry<K, V>> ret = new Iterator<Map.Entry<K, V>>() {
            @Override public boolean hasNext() {
                return iter.hasNext();
            }

            @Override public Map.Entry<K, V> next() {
                return iter.next();
            }

            @Override public void remove() {
                iter.remove();
            }
        };

        itSet.add(new GridWeakIterator(ret, iter, itQ));

        return ret;
    }

    /**
     * Gets lazy iterator for which key and value are lazily deserialized.
     *
     * @param it Closeable iterator.
     * @return Lazy iterator.
     */
    private Iterator<KeyCacheObject> keyIterator(
        final GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> it) {
        if (it == null)
            return new GridEmptyIterator<>();

        checkIteratorQueue();

        // Weak reference will hold hard reference to this iterator, so it can properly be closed.
        final GridCloseableIteratorAdapter<KeyCacheObject> iter = new GridCloseableIteratorAdapter<KeyCacheObject>() {
            private KeyCacheObject cur;

            @Override protected KeyCacheObject onNext() {
                try {
                    cur = cctx.toCacheKeyObject(it.next().getKey());

                    return cur;
                }
                catch(IgniteCheckedException e) {
                    throw new IgniteException(e);
                }
            }

            @Override protected boolean onHasNext() {
                return it.hasNext();
            }

            @Override protected void onRemove() throws IgniteCheckedException {
                throw new IllegalArgumentException();
            }

            @Override protected void onClose() throws IgniteCheckedException {
                it.close();
            }
        };

        // Don't hold hard reference to this iterator - only weak one.
        Iterator<KeyCacheObject> ret = new Iterator<KeyCacheObject>() {
            @Override public boolean hasNext() {
                return iter.hasNext();
            }

            @Override public KeyCacheObject next() {
                return iter.next();
            }

            @Override public void remove() {
                iter.remove();
            }
        };

        itSet.add(new GridWeakIterator(ret, iter, itQ));

        return ret;
    }

    /**
     * Gets offheap iterator over partition.
     *
     * @param part Partition to iterate over.
     * @return Iterator over partition.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> offHeapIterator(
        int part)
        throws IgniteCheckedException {
        if (!offheapEnabled)
            return null;

        checkIteratorQueue();

        return new IteratorWrapper(offheap.iterator(spaceName, part));
    }

    /**
     * @param c Key/value closure.
     * @return Off-heap iterator.
     */
    public <T> GridCloseableIterator<T> rawOffHeapIterator(CX2<T2<Long, Integer>, T2<Long, Integer>, T> c) {
        assert c != null;

        if (!offheapEnabled)
            return new GridEmptyCloseableIterator<>();

        checkIteratorQueue();

        return offheap.iterator(spaceName, c);
    }

    /**
     * @return Raw off-heap iterator.
     */
    public GridCloseableIterator<Map.Entry<byte[], byte[]>> rawOffHeapIterator() {
        if (!offheapEnabled)
            return new GridEmptyCloseableIterator<>();

        return new GridCloseableIteratorAdapter<Map.Entry<byte[], byte[]>>() {
            private GridCloseableIterator<IgniteBiTuple<byte[], byte[]>> it = offheap.iterator(spaceName);

            private Map.Entry<byte[], byte[]> cur;

            @Override protected Map.Entry<byte[], byte[]> onNext() {
                return cur = it.next();
            }

            @Override protected boolean onHasNext() {
                return it.hasNext();
            }

            @Override protected void onRemove() throws IgniteCheckedException {
                KeyCacheObject key = cctx.toCacheKeyObject(cur.getKey());

                int part = cctx.affinity().partition(key);

                offheap.removex(spaceName, part, key, key.valueBytes(cctx.cacheObjectContext()));
            }

            @Override protected void onClose() throws IgniteCheckedException {
                it.close();
            }
        };
    }

    /**
     * Gets swap space iterator over partition.
     *
     * @param part Partition to iterate over.
     * @return Iterator over partition.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> swapIterator(
        int part)
        throws IgniteCheckedException {
        if (!swapEnabled)
            return null;

        checkIteratorQueue();

        return new IteratorWrapper(swapMgr.rawIterator(spaceName, part));
    }

    /**
     * @return Raw off-heap iterator.
     * @throws IgniteCheckedException If failed.
     */
    public GridCloseableIterator<Map.Entry<byte[], byte[]>> rawSwapIterator() throws IgniteCheckedException {
        if (!swapEnabled)
            return new GridEmptyCloseableIterator<>();

        checkIteratorQueue();

        return swapMgr.rawIterator(spaceName);
    }

    /**
     * @param primary If {@code true} includes primary entries.
     * @param backup If {@code true} includes backup entries.
     * @param topVer Topology version.
     * @return Swap entries iterator.
     * @throws IgniteCheckedException If failed.
     */
    public <K, V> Iterator<Cache.Entry<K, V>> swapIterator(boolean primary, boolean backup, AffinityTopologyVersion topVer)
        throws IgniteCheckedException
    {
        assert primary || backup;

        if (!swapEnabled)
            return F.emptyIterator();

        if (primary && backup)
            return cacheEntryIterator(this.<K, V>lazySwapIterator());

        Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
            cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

        return new PartitionsIterator<K, V>(parts) {
            @Override protected GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> partitionIterator(int part)
                throws IgniteCheckedException
            {
                return swapMgr.rawIterator(spaceName, part);
            }
        };
    }

    /**
     * @param primary If {@code true} includes primary entries.
     * @param backup If {@code true} includes backup entries.
     * @param topVer Topology version.
     * @return Offheap entries iterator.
     * @throws IgniteCheckedException If failed.
     */
    public <K, V> Iterator<Cache.Entry<K, V>> offheapIterator(boolean primary, boolean backup, AffinityTopologyVersion topVer)
        throws IgniteCheckedException
    {
        assert primary || backup;

        if (!offheapEnabled)
            return F.emptyIterator();

        if (primary && backup)
            return cacheEntryIterator(this.<K, V>lazyOffHeapIterator());

        Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
            cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

        return new PartitionsIterator<K, V>(parts) {
            @Override protected GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> partitionIterator(int part) {
                return offheap.iterator(spaceName, part);
            }
        };
    }

    /**
     * @param ldr Undeployed class loader.
     * @return Undeploy count.
     */
    public int onUndeploy(ClassLoader ldr) {
        IgniteUuid ldrId = cctx.deploy().getClassLoaderId(ldr);

        assert ldrId != null;

        checkIteratorQueue();

        try {
            GridCloseableIterator<Map.Entry<byte[], byte[]>> iter = rawIterator();

            if (iter != null) {
                int undeployCnt = 0;

                try {
                    for (Map.Entry<byte[], byte[]> e : iter) {
                        try {
                            GridCacheSwapEntry swapEntry = unmarshalSwapEntry(e.getValue());

                            IgniteUuid valLdrId = swapEntry.valueClassLoaderId();

                            if (ldrId.equals(swapEntry.keyClassLoaderId())) {
                                iter.removeX();

                                undeployCnt++;
                            }
                            else {
                                if (valLdrId == null &&
                                    swapEntry.value() == null &&
                                    swapEntry.type() != CacheObject.TYPE_BYTE_ARR) {
                                    // We need value here only for classloading purposes.
                                    Object val =  cctx.cacheObjects().unmarshal(cctx.cacheObjectContext(),
                                        swapEntry.valueBytes(),
                                        cctx.deploy().globalLoader());

                                    if (val != null)
                                        valLdrId = cctx.deploy().getClassLoaderId(val.getClass().getClassLoader());
                                }

                                if (ldrId.equals(valLdrId)) {
                                    iter.removeX();

                                    undeployCnt++;
                                }
                            }
                        }
                        catch (IgniteCheckedException ex) {
                            U.error(log, "Failed to process swap entry.", ex);
                        }
                    }
                }
                finally {
                    iter.close();
                }

                return undeployCnt;
            }
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to clear cache swap space on undeploy.", e);
        }

        return 0;
    }

    /**
     * @return Swap space name.
     */
    public String spaceName() {
        return spaceName;
    }

    /**
     * @param bytes Bytes to unmarshal.
     * @return Unmarshalled entry.
     */
    private GridCacheSwapEntry unmarshalSwapEntry(byte[] bytes) {
        return GridCacheSwapEntryImpl.unmarshal(bytes);
    }

    /**
     * @return Size of internal weak iterator set.
     */
    int iteratorSetSize() {
        return itSet.size();
    }

    /**
     * @param it Map.Entry iterator.
     * @return Cache.Entry iterator.
     */
    private static <K, V> Iterator<Cache.Entry<K, V>> cacheEntryIterator(Iterator<Map.Entry<K, V>> it) {
        return F.iterator(it, new C1<Map.Entry<K, V>, Cache.Entry<K, V>>() {
            @Override public Cache.Entry<K, V> apply(Map.Entry<K, V> e) {
                // Create Cache.Entry over Map.Entry to do not deserialize keys/values if not needed.
                return new CacheEntryImpl0<>(e);
            }
        }, true);
    }

    /**
     *
     */
    private class IteratorWrapper extends GridCloseableIteratorAdapter<Map.Entry<byte[], GridCacheSwapEntry>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> iter;

        /**
         * @param iter Iterator.
         */
        private IteratorWrapper(GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> iter) {
            assert iter != null;

            this.iter = iter;
        }

        /** {@inheritDoc} */
        @Override protected Map.Entry<byte[], GridCacheSwapEntry> onNext() throws IgniteCheckedException {
            Map.Entry<byte[], byte[]> e = iter.nextX();

            GridCacheSwapEntry unmarshalled = unmarshalSwapEntry(e.getValue());

            return F.t(e.getKey(), swapEntry(unmarshalled));
        }

        /** {@inheritDoc} */
        @Override protected boolean onHasNext() throws IgniteCheckedException {
            return iter.hasNext();
        }

        /** {@inheritDoc} */
        @Override protected void onClose() throws IgniteCheckedException {
            iter.close();
        }

        /** {@inheritDoc} */
        @Override protected void onRemove() {
            iter.remove();
        }
    }

    /**
     *
     */
    private class KeySwapListener implements GridCacheSwapListener {
        /** */
        private final KeyCacheObject key;

        /** */
        private volatile GridCacheSwapEntry entry;

        /**
         * @param key Key.
         */
        KeySwapListener(KeyCacheObject key) {
            this.key = key;
        }

        /** {@inheritDoc} */
        @Override public void onEntryUnswapped(int part,
            KeyCacheObject key,
            GridCacheSwapEntry e) throws IgniteCheckedException {
            if (this.key.equals(key)) {
                GridCacheSwapEntryImpl e0 = new GridCacheSwapEntryImpl(ByteBuffer.wrap(e.valueBytes()),
                    e.type(),
                    e.version(),
                    e.ttl(),
                    e.expireTime(),
                    e.keyClassLoaderId(),
                    e.valueClassLoaderId());

                CacheObject v = e.value();

                if (v != null)
                    e0.value(v);
                else
                    e0 = swapEntry(e0);

                assert e0 != null && e0.value() != null : e0;

                entry = e0;
            }
        }
    }

    /**
     *
     */
    private abstract class PartitionsIterator<K, V> implements Iterator<Cache.Entry<K, V>> {
        /** */
        private Iterator<Integer> partIt;

        /** */
        private Iterator<Cache.Entry<K, V>> curIt;

        /** */
        private Cache.Entry<K, V> next;

        /**
         * @param parts Partitions
         */
        public PartitionsIterator(Collection<Integer> parts) {
            this.partIt = parts.iterator();

            advance();
        }

        /** {@inheritDoc} */
        @Override public boolean hasNext() {
            return next != null;
        }

        /** {@inheritDoc} */
        @Override public Cache.Entry<K, V> next() {
            if (next == null)
                throw new NoSuchElementException();

            Cache.Entry<K, V> e = next;

            advance();

            return e;
        }

        /** {@inheritDoc} */
        @Override public void remove() {
            throw new UnsupportedOperationException();
        }

        /**
         * Switches to next element.
         */
        private void advance() {
            next = null;

            do {
                if (curIt == null) {
                    if (partIt.hasNext()) {
                        int part = partIt.next();

                        try {
                            curIt = cacheEntryIterator(
                                GridCacheSwapManager.this.<K, V>lazyIterator(partitionIterator(part)));
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteException(e);
                        }
                    }
                }

                if (curIt != null) {
                    if (curIt.hasNext()) {
                        next = curIt.next();

                        break;
                    }
                    else
                        curIt = null;
                }
            }
            while (partIt.hasNext());
        }

        /**
         * @param part Partition.
         * @return Iterator for given partition.
         * @throws IgniteCheckedException If failed.
         */
        abstract protected GridCloseableIterator<? extends Map.Entry<byte[], byte[]>> partitionIterator(int part)
            throws IgniteCheckedException;
    }

    /**
     *
     */
    private abstract class PartitionsKeyIterator implements Iterator<KeyCacheObject> {
        /** */
        private Iterator<Integer> partIt;

        /** */
        private Iterator<KeyCacheObject> curIt;

        /** */
        private KeyCacheObject next;

        /**
         * @param parts Partitions
         */
        public PartitionsKeyIterator(Collection<Integer> parts) {
            this.partIt = parts.iterator();

            advance();
        }

        /** {@inheritDoc} */
        @Override public boolean hasNext() {
            return next != null;
        }

        /** {@inheritDoc} */
        @Override public KeyCacheObject next() {
            if (next == null)
                throw new NoSuchElementException();

            KeyCacheObject e = next;

            advance();

            return e;
        }

        /** {@inheritDoc} */
        @Override public void remove() {
            throw new UnsupportedOperationException();
        }

        /**
         * Switches to next element.
         */
        private void advance() {
            next = null;

            do {
                if (curIt == null) {
                    if (partIt.hasNext()) {
                        int part = partIt.next();

                        try {
                            curIt = partitionIterator(part);
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteException(e);
                        }
                    }
                }

                if (curIt != null) {
                    if (curIt.hasNext()) {
                        next = curIt.next();

                        break;
                    }
                    else
                        curIt = null;
                }
            }
            while (partIt.hasNext());
        }

        /**
         * @param part Partition.
         * @return Iterator for given partition.
         * @throws IgniteCheckedException If failed.
         */
        abstract protected Iterator<KeyCacheObject> partitionIterator(int part)
            throws IgniteCheckedException;
    }
}
