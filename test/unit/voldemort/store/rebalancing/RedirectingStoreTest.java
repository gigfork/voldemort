/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.rebalancing;

import java.io.IOException;
import java.util.Arrays;

import junit.framework.TestCase;
import voldemort.ServerTestUtils;
import voldemort.TestUtils;
import voldemort.client.protocol.RequestFormatType;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.server.VoldemortServer;
import voldemort.store.Store;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.socket.SocketPool;
import voldemort.utils.ByteArray;
import voldemort.utils.ByteUtils;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;

/**
 * @author bbansal
 * 
 */
public class RedirectingStoreTest extends TestCase {

    private static String storeName = "test-replication-memory";
    private static String storesXmlfile = "test/common/voldemort/config/stores.xml";

    VoldemortServer server0;
    VoldemortServer server1;
    AdminClient adminClient;

    Cluster cluster;

    @Override
    public void setUp() throws IOException {
        // start 2 node cluster with free ports
        int[] ports = ServerTestUtils.findFreePorts(2);
        Node node0 = new Node(0, "localhost", ports[0], ports[1], Arrays.asList(new Integer[] { 0,
                1 }));

        ports = ServerTestUtils.findFreePorts(2);
        Node node1 = new Node(1, "localhost", ports[0], ports[1], Arrays.asList(new Integer[] { 2,
                3 }));

        cluster = new Cluster("admin-service-test", Arrays.asList(new Node[] { node0, node1 }));

        server0 = new VoldemortServer(ServerTestUtils.createServerConfig(0,
                                                                         TestUtils.createTempDir()
                                                                                  .getAbsolutePath(),
                                                                         null,
                                                                         storesXmlfile),
                                      cluster);
        server0.start();

        server1 = new VoldemortServer(ServerTestUtils.createServerConfig(1,
                                                                         TestUtils.createTempDir()
                                                                                  .getAbsolutePath(),
                                                                         null,
                                                                         storesXmlfile),
                                      cluster);
        server1.start();

        // get adminClient
        adminClient = ServerTestUtils.getAdminClient(server0.getIdentityNode(),
                                                     server0.getMetadataStore());

    }

    @Override
    public void tearDown() throws IOException, InterruptedException {
        server0.stop();
        server1.stop();
    }

    private RedirectingStore getRedirectingStore(MetadataStore metadata) {
        return new RedirectingStore(ServerTestUtils.getSocketStore(storeName,
                                                                   server0.getIdentityNode()
                                                                          .getSocketPort(),
                                                                   RequestFormatType.VOLDEMORT_V1),
                                    metadata,
                                    new SocketPool(100, 2000, 1000, 10000));
    }

    public void testProxyGet() {
        // enter bunch of data into server1
        for(int i = 100; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));
            byte[] value = ByteUtils.getBytes("value-" + i, "UTF-8");

            Store<ByteArray, byte[]> store = server1.getStoreRepository()
                                                    .getStorageEngine(storeName);
            store.put(key,
                      Versioned.value(value,
                                      new VectorClock().incremented(0, System.currentTimeMillis())));
        }

        MetadataStore metadata = server0.getMetadataStore();

        // change donorNode/stealPartitionList here.
        // TODO : fix me
        // adminClient.updateRebalancingProxyDest(0, 1);
        // adminClient.updateRebalancingPartitionList(0, Arrays.asList(new
        // Integer[] { 2, 3 }));

        RedirectingStore RedirectingStore = getRedirectingStore(metadata);

        // for Normal server state no values are expected
        for(int i = 100; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));

            if(metadata.getRoutingStrategy(storeName)
                       .getPartitionList(key.get())
                       .contains(new Integer(2))
               || metadata.getRoutingStrategy(storeName)
                          .getPartitionList(key.get())
                          .contains(new Integer(3))) {
                assertEquals("proxyGet should return emptys list", 0, RedirectingStore.get(key)
                                                                                      .size());
            }
        }

        adminClient.updateRemoteServerState(0,
                                            MetadataStore.VoldemortState.REBALANCING_MASTER_SERVER);
        for(int i = 100; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));

            if(metadata.getRoutingStrategy(storeName)
                       .getPartitionList(key.get())
                       .contains(new Integer(2))
               || metadata.getRoutingStrategy(storeName)
                          .getPartitionList(key.get())
                          .contains(new Integer(3))) {
                assertEquals("proxyGet should return actual value",
                             "value-" + i,
                             new String(RedirectingStore.get(key).get(0).getValue()));
            }
        }
    }

    public void testProxyPut() {
        // enter bunch of data into server1
        for(int i = 100; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));
            byte[] value = ByteUtils.getBytes("value-" + i, "UTF-8");

            Store<ByteArray, byte[]> store = server1.getStoreRepository()
                                                    .getStorageEngine(storeName);
            store.put(key,
                      Versioned.value(value,
                                      new VectorClock().incremented(0, System.currentTimeMillis())));

            // 
            try {
                store.put(key, store.get(key).get(0));
                fail("put should throw ObsoleteVersionException before hitting this");
            } catch(ObsoleteVersionException e) {
                // ignore
            }
        }

        MetadataStore metadata = server0.getMetadataStore();

        // change donorNode/stealPartitionList here.
        // TODO : fix me
        // adminClient.updateRebalancingProxyDest(0, 1);
        // adminClient.updateRebalancingPartitionList(0, Arrays.asList(new
        // Integer[] { 2, 3 }));

        RedirectingStore RedirectingStore = getRedirectingStore(metadata);

        // we should see obsolete version exception if try to insert with same
        // version

        adminClient.updateRemoteServerState(0,
                                            MetadataStore.VoldemortState.REBALANCING_MASTER_SERVER);
        for(int i = 100; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));
            if(metadata.getRoutingStrategy(storeName)
                       .getPartitionList(key.get())
                       .contains(new Integer(2))
               || metadata.getRoutingStrategy(storeName)
                          .getPartitionList(key.get())
                          .contains(new Integer(3))) {
                try {
                    RedirectingStore.put(key, RedirectingStore.get(key).get(0));
                    fail("put should throw ObsoleteVersionException before hitting this");
                } catch(ObsoleteVersionException e) {
                    // ignore
                }
            }
        }
    }
}
