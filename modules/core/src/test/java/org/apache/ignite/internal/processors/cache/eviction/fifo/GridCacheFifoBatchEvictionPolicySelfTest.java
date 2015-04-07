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

package org.apache.ignite.internal.processors.cache.eviction.fifo;

import org.apache.ignite.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.cache.eviction.fifo.*;
import org.apache.ignite.internal.processors.cache.eviction.*;

import java.util.*;

import static org.apache.ignite.cache.CacheMode.*;

/**
 * FIFO batch eviction test.
 */
public class GridCacheFifoBatchEvictionPolicySelfTest extends
        GridCacheEvictionAbstractTest<FifoEvictionPolicy<String, String>> {
    /**
     * @throws Exception If failed.
     */
    public void testPolicy() throws Exception {
        try {
            startGrid();

            MockEntry e1 = new MockEntry("1", "1");
            MockEntry e2 = new MockEntry("2", "2");
            MockEntry e3 = new MockEntry("3", "3");
            MockEntry e4 = new MockEntry("4", "4");
            MockEntry e5 = new MockEntry("5", "5");

            FifoEvictionPolicy<String, String> p = policy();

            p.setMaxSize(3);

            p.setBatchSize(2);

            p.onEntryAccessed(false, e1);

            check(p.queue(), e1);

            p.onEntryAccessed(false, e2);

            check(p.queue(), e1, e2);

            p.onEntryAccessed(false, e3);

            check(p.queue(), e1, e2, e3);

            p.onEntryAccessed(false, e4);

            check(p.queue(), e1, e2, e3, e4);

            assertFalse(e1.isEvicted());
            assertFalse(e2.isEvicted());
            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());

            assertEquals(4, p.getCurrentSize());

            p.onEntryAccessed(false, e5);

            // Batch evicted.
            check(p.queue(), e3, e4, e5);

            assertEquals(3, p.getCurrentSize());

            assertTrue(e1.isEvicted());
            assertTrue(e2.isEvicted());
            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());
            assertFalse(e5.isEvicted());

            p.onEntryAccessed(false, e1 = new MockEntry("1", "1"));

            check(p.queue(), e3, e4, e5, e1);

            assertEquals(4, p.getCurrentSize());

            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());
            assertFalse(e5.isEvicted());
            assertFalse(e1.isEvicted());

            p.onEntryAccessed(false, e5);

            check(p.queue(), e3, e4, e5, e1);

            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());
            assertFalse(e5.isEvicted());
            assertFalse(e1.isEvicted());

            p.onEntryAccessed(false, e1);

            assertEquals(4, p.getCurrentSize());

            check(p.queue(), e3, e4, e5, e1);

            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());
            assertFalse(e5.isEvicted());
            assertFalse(e1.isEvicted());

            p.onEntryAccessed(true, e1);

            assertEquals(3, p.getCurrentSize());

            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());
            assertFalse(e5.isEvicted());

            p.onEntryAccessed(true, e4);

            assertEquals(2, p.getCurrentSize());

            assertFalse(e3.isEvicted());
            assertFalse(e5.isEvicted());

            p.onEntryAccessed(true, e5);

            assertEquals(1, p.getCurrentSize());

            assertFalse(e3.isEvicted());

            p.onEntryAccessed(true, e3);

            assertEquals(0, p.getCurrentSize());

            assertFalse(e3.isEvicted());

            info(p);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testMemory() throws Exception {
        try {
            startGrid();

            FifoEvictionPolicy<String, String> p = policy();

            int max = 10;

            int batchSize = 2;

            p.setMaxSize(max);
            p.setBatchSize(batchSize);

            int cnt = max + batchSize;

            for (int i = 0; i < cnt; i++)
                p.onEntryAccessed(false, new MockEntry(Integer.toString(i), Integer.toString(i)));

            info(p);

            assertEquals(cnt - batchSize, p.getCurrentSize());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testRandom() throws Exception {
        try {
            startGrid();

            FifoEvictionPolicy<String, String> p = policy();

            int max = 10;

            int batchSize = 2;

            p.setMaxSize(max);

            p.setBatchSize(batchSize);

            Random rand = new Random();

            int keys = 31;

            MockEntry[] fifos = new MockEntry[keys];

            for (int i = 0; i < fifos.length; i++)
                fifos[i] = new MockEntry(Integer.toString(i));

            int runs = 5000000;

            for (int i = 0; i < runs; i++) {
                boolean rmv = rand.nextBoolean();

                int j = rand.nextInt(fifos.length);

                MockEntry e = entry(fifos, j);

                if (rmv)
                    fifos[j] = new MockEntry(Integer.toString(j));

                p.onEntryAccessed(rmv, e);
            }

            info(p);

            int curSize = p.getCurrentSize();

            assert curSize < max + batchSize :
                "curSize < max + batchSize [curSize=" + curSize + ", max=" + max + ", batchSize=" + batchSize + ']';
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testAllowEmptyEntries() throws Exception {
        try {
            startGrid();

            MockEntry e1 = new MockEntry("1");

            MockEntry e2 = new MockEntry("2");

            MockEntry e3 = new MockEntry("3");

            MockEntry e4 = new MockEntry("4");

            MockEntry e5 = new MockEntry("5");

            FifoEvictionPolicy<String, String> p = policy();

            p.setBatchSize(2);

            p.onEntryAccessed(false, e1);

            assertFalse(e1.isEvicted());

            p.onEntryAccessed(false, e2);

            assertFalse(e1.isEvicted());
            assertFalse(e2.isEvicted());

            p.onEntryAccessed(false, e3);

            assertFalse(e1.isEvicted());
            assertFalse(e3.isEvicted());

            p.onEntryAccessed(false, e4);

            assertFalse(e1.isEvicted());
            assertFalse(e3.isEvicted());
            assertFalse(e4.isEvicted());

            p.onEntryAccessed(false, e5);

            assertFalse(e1.isEvicted());
            assertFalse(e3.isEvicted());
            assertFalse(e5.isEvicted());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testPut() throws Exception {
        mode = LOCAL;
        syncCommit = true;
        plcMax = 10;

        Ignite ignite = startGrid();

        try {
            IgniteCache<Object, Object> cache = ignite.cache(null);

            int cnt = 500;

            int min = Integer.MAX_VALUE;

            int minIdx = 0;

            for (int i = 0; i < cnt; i++) {
                cache.put(i, i);

                int cacheSize = cache.size();

                if (i > plcMax && cacheSize < min) {
                    min = cacheSize;
                    minIdx = i;
                }
            }

            // Batch evicted.
            assert min >= plcMax : "Min cache size is too small: " + min;

            info("Min cache size [min=" + min + ", idx=" + minIdx + ']');
            info("Current cache size " + cache.size());
            info("Current cache key size " + cache.size());

            min = Integer.MAX_VALUE;

            minIdx = 0;

            // Touch.
            for (int i = cnt; --i > cnt - plcMax;) {
                cache.get(i);

                int cacheSize = cache.size();

                if (cacheSize < min) {
                    min = cacheSize;
                    minIdx = i;
                }
            }

            info("----");
            info("Min cache size [min=" + min + ", idx=" + minIdx + ']');
            info("Current cache size " + cache.size());
            info("Current cache key size " + cache.size());

            // Batch evicted.
            assert min >= plcMax : "Min cache size is too small: " + min;
        }
        finally {
            stopAllGrids();
        }
    }

    /** {@inheritDoc} */
    @Override public void testPartitionedNearDisabled() throws Exception {
        plcBatchSize = 2;

        super.testPartitionedNearDisabled();
    }

    /** {@inheritDoc} */
    @Override protected FifoEvictionPolicy<String, String> createPolicy(int plcMax) {
        return new FifoEvictionPolicy<>(10, 2);
    }

    /** {@inheritDoc} */
    @Override protected FifoEvictionPolicy<String, String> createNearPolicy(int nearMax) {
        return new FifoEvictionPolicy<>(nearMax, 2);
    }

    /** {@inheritDoc} */
    @Override protected void checkNearPolicies(int endNearPlcSize) {
        for (int i = 0; i < gridCnt; i++)
            for (EvictableEntry<String, String> e : nearPolicy(i).queue())
                assert !e.isCached() : "Invalid near policy size: " + nearPolicy(i).queue();
    }

    /** {@inheritDoc} */
    @Override protected void checkPolicies(int plcMax) {
        for (int i = 0; i < gridCnt; i++)
            assert policy(i).queue().size() <= plcMax + policy(i).getBatchSize();
    }

}