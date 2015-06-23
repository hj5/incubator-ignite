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

package org.apache.ignite.internal;

import org.apache.ignite.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.events.EventType;
import org.apache.ignite.internal.managers.communication.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.nio.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.communication.tcp.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.*;
import org.apache.ignite.testframework.junits.common.*;

import javax.cache.event.*;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.*;

/**
 *
 */
public class IgniteSlowClientDetectionSelfTest extends GridCommonAbstractTest {
    /** */
    public static final String PARTITIONED = "partitioned";

    /** */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /**
     * @return Node count.
     */
    private int nodeCount() {
        return 5;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(ipFinder);

        if (getTestGridName(nodeCount() - 1).equals(gridName) || getTestGridName(nodeCount() - 2).equals(gridName))
            cfg.setClientMode(true);

        TcpCommunicationSpi commSpi = new TcpCommunicationSpi();

        commSpi.setSlowClientQueueLimit(50);
        commSpi.setSharedMemoryPort(-1);
        commSpi.setIdleConnectionTimeout(300_000);

        cfg.setCommunicationSpi(commSpi);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        startGrids(nodeCount());
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();

        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testSlowClient() throws Exception {
        final IgniteEx slowClient = grid(nodeCount() - 1);

        final ClusterNode slowClientNode = slowClient.localNode();

        final CountDownLatch evtSegmentedLatch = new CountDownLatch(1);

        slowClient.events().localListen(new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                assertEquals("Unexpected event: " + evt, evt.type(), EventType.EVT_NODE_SEGMENTED);

                DiscoveryEvent evt0 = (DiscoveryEvent)evt;

                assertEquals(slowClientNode, evt0.eventNode());
                assertEquals(5L, evt0.topologyVersion());

                evtSegmentedLatch.countDown();

                return false;
            }
        }, EventType.EVT_NODE_SEGMENTED);

        final CountDownLatch evtFailedLatch = new CountDownLatch(nodeCount() - 1);

        for (int i = 0; i < nodeCount() - 1; i++) {
            grid(i).events().localListen(new IgnitePredicate<Event>() {
                @Override public boolean apply(Event evt) {
                    assertEquals("Unexpected event: " + evt, evt.type(), EventType.EVT_NODE_FAILED);

                    DiscoveryEvent evt0 = (DiscoveryEvent) evt;

                    assertEquals(slowClientNode, evt0.eventNode());
                    assertEquals(6L, evt0.topologyVersion());
                    assertEquals(4, evt0.topologyNodes().size());

                    evtFailedLatch.countDown();

                    return false;
                }
            }, EventType.EVT_NODE_FAILED);
        }

        assertTrue(slowClient.cluster().localNode().isClient());

        IgniteCache<Object, Object> cache = slowClient.getOrCreateCache(PARTITIONED);

        IgniteEx client0 = grid(nodeCount() - 2);

        assertTrue(client0.cluster().localNode().isClient());

        IgniteCache<Object, Object> cache0 = client0.getOrCreateCache(PARTITIONED);

        cache.query(new ContinuousQuery<>().setLocalListener(new Listener()));

        for (int i = 0; i < 100; i++)
            cache0.put(0, i);

        GridIoManager ioMgr = slowClient.context().io();

        TcpCommunicationSpi commSpi = (TcpCommunicationSpi)((Object[])U.field(ioMgr, "spis"))[0];

        GridNioServer nioSrvr = U.field(commSpi, "nioSrvr");

        GridTestUtils.setFieldValue(nioSrvr, "skipRead", true);

        // Initiate messages for client.
        for (int i = 0; i < 100; i++)
            cache0.put(0, new byte[10 * 1024]);

        boolean wait = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return Ignition.state(slowClient.name()) == IgniteState.STOPPED_ON_SEGMENTATION;
            }
        }, getTestTimeout());

        assertTrue(wait);

        assertTrue("Failed to wait for client failed event", evtFailedLatch.await(5000, MILLISECONDS));
        assertTrue("Failed to wait for client segmented event", evtSegmentedLatch.await(5000, MILLISECONDS));
    }

    /**
     *
     */
    private static class Listener implements CacheEntryUpdatedListener<Object, Object> {
        /** {@inheritDoc} */
        @Override public void onUpdated(Iterable iterable) throws CacheEntryListenerException {
            System.out.println(">>>> Received update: " + iterable);
        }
    }
}
