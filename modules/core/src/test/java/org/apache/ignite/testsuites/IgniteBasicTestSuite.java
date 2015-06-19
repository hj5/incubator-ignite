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

package org.apache.ignite.testsuites;

import junit.framework.*;
import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.closure.*;
import org.apache.ignite.internal.processors.continuous.*;
import org.apache.ignite.internal.processors.service.*;
import org.apache.ignite.internal.product.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.messaging.*;
import org.apache.ignite.spi.*;
import org.apache.ignite.testframework.*;

import java.util.*;

/**
 * Basic test suite.
 */
public class IgniteBasicTestSuite extends TestSuite {
    /**
     * @return Test suite.
     * @throws Exception Thrown in case of the failure.
     */
    public static TestSuite suite() throws Exception {
        return suite(null);
    }

    /**
     * @param ignoredTests Tests don't include in the execution.
     * @return Test suite.
     * @throws Exception Thrown in case of the failure.
     */
    public static TestSuite suite(Set<Class> ignoredTests) throws Exception {
        TestSuite suite = new TestSuite("Ignite Basic Test Suite");

        suite.addTest(IgniteLangSelfTestSuite.suite());
        suite.addTest(IgniteUtilSelfTestSuite.suite(ignoredTests));
        suite.addTest(IgniteMarshallerSelfTestSuite.suite(ignoredTests));

        suite.addTest(IgniteKernalSelfTestSuite.suite(ignoredTests));
        suite.addTest(IgniteStartUpTestSuite.suite());
        suite.addTest(IgniteExternalizableSelfTestSuite.suite());
        suite.addTest(IgniteP2PSelfTestSuite.suite());
        suite.addTest(IgniteCacheP2pUnmarshallingErrorTestSuite.suite(ignoredTests));
        suite.addTest(IgniteStreamSelfTestSuite.suite());

        suite.addTest(new TestSuite(GridSelfTest.class));
        GridTestUtils.addTestIfNeeded(suite, GridProjectionSelfTest.class, ignoredTests);
        suite.addTest(new TestSuite(ClusterForHostsSelfTest.class));
        GridTestUtils.addTestIfNeeded(suite, GridMessagingSelfTest.class, ignoredTests);
        suite.addTest(new TestSuite(IgniteMessagingWithClientTest.class));
        GridTestUtils.addTestIfNeeded(suite, GridMessagingNoPeerClassLoadingSelfTest.class, ignoredTests);

        if (U.isLinux() || U.isMacOs())
            suite.addTest(IgniteIpcSharedMemorySelfTestSuite.suite());

        GridTestUtils.addTestIfNeeded(suite, GridReleaseTypeSelfTest.class, ignoredTests);
        suite.addTestSuite(GridProductVersionSelfTest.class);
        suite.addTestSuite(GridAffinityProcessorRendezvousSelfTest.class);
        suite.addTestSuite(GridClosureProcessorSelfTest.class);
        suite.addTestSuite(ClosureServiceClientsNodesTest.class);
        suite.addTestSuite(GridStartStopSelfTest.class);
        suite.addTestSuite(GridProjectionForCachesSelfTest.class);
        suite.addTestSuite(GridProjectionForCachesOnDaemonNodeSelfTest.class);
        suite.addTestSuite(GridSpiLocalHostInjectionTest.class);
        suite.addTestSuite(GridLifecycleBeanSelfTest.class);
        suite.addTestSuite(GridStopWithCancelSelfTest.class);
        suite.addTestSuite(GridReduceSelfTest.class);
        suite.addTestSuite(GridEventConsumeSelfTest.class);
        suite.addTestSuite(GridSuppressedExceptionSelfTest.class);
        suite.addTestSuite(GridLifecycleAwareSelfTest.class);
        suite.addTestSuite(GridMessageListenSelfTest.class);
        suite.addTestSuite(GridFailFastNodeFailureDetectionSelfTest.class);
        suite.addTestSuite(OffHeapTieredTransactionSelfTest.class);

        return suite;
    }
}
