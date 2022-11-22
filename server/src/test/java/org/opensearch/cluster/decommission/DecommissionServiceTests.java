/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.decommission;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.cluster.decommission.awareness.delete.DeleteDecommissionStateResponse;
import org.opensearch.action.admin.cluster.decommission.awareness.put.DecommissionRequest;
import org.opensearch.action.admin.cluster.decommission.awareness.put.DecommissionResponse;
import org.opensearch.action.admin.cluster.configuration.ClearVotingConfigExclusionsRequest;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.metadata.WeightedRoutingMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.WeightedRouting;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.test.ClusterServiceUtils;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.transport.MockTransport;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;
import static org.opensearch.cluster.ClusterState.builder;
import static org.opensearch.cluster.OpenSearchAllocationTestCase.createAllocationService;
import static org.opensearch.test.ClusterServiceUtils.createClusterService;
import static org.opensearch.test.ClusterServiceUtils.setState;

public class DecommissionServiceTests extends OpenSearchTestCase {

    private ThreadPool threadPool;
    private ClusterService clusterService;
    private TransportService transportService;
    private AllocationService allocationService;
    private DecommissionService decommissionService;
    private ClusterSettings clusterSettings;

    @Before
    public void setUpService() {
        threadPool = new TestThreadPool("test", Settings.EMPTY);
        clusterService = createClusterService(threadPool);
        allocationService = createAllocationService();
        ClusterState clusterState = ClusterState.builder(new ClusterName("test")).build();
        logger.info("--> adding cluster manager node on zone_1");
        clusterState = addClusterManagerNodes(clusterState, "zone_1", "node1");
        logger.info("--> adding cluster manager node on zone_2");
        clusterState = addClusterManagerNodes(clusterState, "zone_2", "node6");
        logger.info("--> adding cluster manager node on zone_3");
        clusterState = addClusterManagerNodes(clusterState, "zone_3", "node11");
        logger.info("--> adding four data nodes on zone_1");
        clusterState = addDataNodes(clusterState, "zone_1", "node2", "node3", "node4", "node5");
        logger.info("--> adding four data nodes on zone_2");
        clusterState = addDataNodes(clusterState, "zone_2", "node7", "node8", "node9", "node10");
        logger.info("--> adding four data nodes on zone_3");
        clusterState = addDataNodes(clusterState, "zone_3", "node12", "node13", "node14", "node15");
        clusterState = setLocalNodeAsClusterManagerNode(clusterState, "node1");
        clusterState = setNodesInVotingConfig(
            clusterState,
            clusterState.nodes().get("node1"),
            clusterState.nodes().get("node6"),
            clusterState.nodes().get("node11")
        );
        final ClusterState.Builder builder = builder(clusterState);
        setState(clusterService, builder);
        final MockTransport transport = new MockTransport();
        transportService = transport.createTransportService(
            Settings.EMPTY,
            threadPool,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            boundTransportAddress -> clusterService.state().nodes().get("node1"),
            null,
            emptySet()
        );

        final Settings.Builder nodeSettingsBuilder = Settings.builder()
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.getKey(), "zone")
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone_1,zone_2,zone_3");

        clusterSettings = new ClusterSettings(nodeSettingsBuilder.build(), ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        transportService.start();
        transportService.acceptIncomingRequests();

        this.decommissionService = new DecommissionService(
            nodeSettingsBuilder.build(),
            clusterSettings,
            clusterService,
            transportService,
            threadPool,
            allocationService
        );
    }

    @After
    public void shutdownThreadPoolAndClusterService() {
        clusterService.stop();
        threadPool.shutdown();
    }

    @SuppressWarnings("unchecked")
    public void testDecommissioningNotStartedForInvalidAttributeName() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("rack", "rack-a");
        ActionListener<DecommissionResponse> listener = new ActionListener<DecommissionResponse>() {
            @Override
            public void onResponse(DecommissionResponse decommissionResponse) {
                fail("on response shouldn't have been called");
            }

            @Override
            public void onFailure(Exception e) {
                assertTrue(e instanceof DecommissioningFailedException);
                assertThat(e.getMessage(), Matchers.endsWith("invalid awareness attribute requested for decommissioning"));
                countDownLatch.countDown();
            }
        };
        decommissionService.startDecommissionAction(new DecommissionRequest(decommissionAttribute), listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    public void testDecommissioningNotStartedForInvalidAttributeValue() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "rack-a");
        ActionListener<DecommissionResponse> listener = new ActionListener<DecommissionResponse>() {
            @Override
            public void onResponse(DecommissionResponse decommissionResponse) {
                fail("on response shouldn't have been called");
            }

            @Override
            public void onFailure(Exception e) {
                assertTrue(e instanceof DecommissioningFailedException);
                assertThat(
                    e.getMessage(),
                    Matchers.endsWith(
                        "invalid awareness attribute value requested for decommissioning. "
                            + "Set forced awareness values before to decommission"
                    )
                );
                countDownLatch.countDown();
            }
        };
        decommissionService.startDecommissionAction(new DecommissionRequest(decommissionAttribute), listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    public void testDecommissionNotStartedWithoutWeighingAwayAttribute_1() throws InterruptedException {
        Map<String, Double> weights = Map.of("zone_1", 1.0, "zone_2", 1.0, "zone_3", 0.0);
        setWeightedRoutingWeights(weights);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "zone_1");
        ActionListener<DecommissionResponse> listener = new ActionListener<DecommissionResponse>() {
            @Override
            public void onResponse(DecommissionResponse decommissionResponse) {
                fail("on response shouldn't have been called");
            }

            @Override
            public void onFailure(Exception e) {
                assertTrue(e instanceof DecommissioningFailedException);
                assertThat(
                    e.getMessage(),
                    Matchers.containsString("weight for decommissioned attribute is expected to be [0.0] but found [1.0]")
                );
                countDownLatch.countDown();
            }
        };
        decommissionService.startDecommissionAction(new DecommissionRequest(decommissionAttribute), listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    public void testDecommissionNotStartedWithoutWeighingAwayAttribute_2() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "zone_1");
        ActionListener<DecommissionResponse> listener = new ActionListener<DecommissionResponse>() {
            @Override
            public void onResponse(DecommissionResponse decommissionResponse) {
                fail("on response shouldn't have been called");
            }

            @Override
            public void onFailure(Exception e) {
                assertTrue(e instanceof DecommissioningFailedException);
                assertThat(
                    e.getMessage(),
                    Matchers.containsString(
                        "no weights are set to the attribute. Please set appropriate weights before triggering decommission action"
                    )
                );
                countDownLatch.countDown();
            }
        };
        decommissionService.startDecommissionAction(new DecommissionRequest(decommissionAttribute), listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    public void testDecommissioningFailedWhenAnotherAttributeDecommissioningSuccessful() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionStatus oldStatus = randomFrom(DecommissionStatus.SUCCESSFUL, DecommissionStatus.IN_PROGRESS, DecommissionStatus.INIT);
        DecommissionAttributeMetadata oldMetadata = new DecommissionAttributeMetadata(
            new DecommissionAttribute("zone", "zone_1"),
            oldStatus
        );
        final ClusterState.Builder builder = builder(clusterService.state());
        setState(
            clusterService,
            builder.metadata(Metadata.builder(clusterService.state().metadata()).decommissionAttributeMetadata(oldMetadata).build())
        );
        ActionListener<DecommissionResponse> listener = new ActionListener<DecommissionResponse>() {
            @Override
            public void onResponse(DecommissionResponse decommissionResponse) {
                fail("on response shouldn't have been called");
            }

            @Override
            public void onFailure(Exception e) {
                assertTrue(e instanceof DecommissioningFailedException);
                if (oldStatus.equals(DecommissionStatus.SUCCESSFUL)) {
                    assertThat(
                        e.getMessage(),
                        Matchers.endsWith("already successfully decommissioned, recommission before triggering another decommission")
                    );
                } else {
                    assertThat(e.getMessage(), Matchers.endsWith("is in progress, cannot process this request"));
                }
                countDownLatch.countDown();
            }
        };
        DecommissionRequest request = new DecommissionRequest(new DecommissionAttribute("zone", "zone_2"));
        decommissionService.startDecommissionAction(request, listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    public void testScheduleNodesDecommissionOnTimeout() {
        TransportService mockTransportService = Mockito.mock(TransportService.class);
        ThreadPool mockThreadPool = Mockito.mock(ThreadPool.class);
        Mockito.when(mockTransportService.getLocalNode()).thenReturn(Mockito.mock(DiscoveryNode.class));
        Mockito.when(mockTransportService.getThreadPool()).thenReturn(mockThreadPool);
        DecommissionService decommissionService = new DecommissionService(
            Settings.EMPTY,
            clusterSettings,
            clusterService,
            mockTransportService,
            threadPool,
            allocationService
        );
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "zone-2");
        DecommissionAttributeMetadata decommissionAttributeMetadata = new DecommissionAttributeMetadata(
            decommissionAttribute,
            DecommissionStatus.DRAINING
        );
        Metadata metadata = Metadata.builder().putCustom(DecommissionAttributeMetadata.TYPE, decommissionAttributeMetadata).build();
        ClusterState state = ClusterState.builder(new ClusterName("test")).metadata(metadata).build();

        DiscoveryNode decommissionedNode1 = Mockito.mock(DiscoveryNode.class);
        DiscoveryNode decommissionedNode2 = Mockito.mock(DiscoveryNode.class);

        setState(clusterService, state);
        decommissionService.scheduleNodesDecommissionOnTimeout(
            Set.of(decommissionedNode1, decommissionedNode2),
            DecommissionRequest.DEFAULT_NODE_DRAINING_TIMEOUT
        );

        Mockito.verify(mockThreadPool).schedule(Mockito.any(Runnable.class), Mockito.any(TimeValue.class), Mockito.anyString());
    }

    public void testDrainNodesWithDecommissionedAttributeWithNoDelay() {
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "zone-2");
        DecommissionAttributeMetadata decommissionAttributeMetadata = new DecommissionAttributeMetadata(
            decommissionAttribute,
            DecommissionStatus.INIT
        );

        Metadata metadata = Metadata.builder().putCustom(DecommissionAttributeMetadata.TYPE, decommissionAttributeMetadata).build();
        ClusterState state = ClusterState.builder(new ClusterName("test")).metadata(metadata).build();

        DecommissionRequest request = new DecommissionRequest(decommissionAttribute);
        request.setNoDelay(true);

        setState(clusterService, state);
        decommissionService.drainNodesWithDecommissionedAttribute(request);

    }

    public void testClearClusterDecommissionState() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        DecommissionAttribute decommissionAttribute = new DecommissionAttribute("zone", "zone-2");
        DecommissionAttributeMetadata decommissionAttributeMetadata = new DecommissionAttributeMetadata(
            decommissionAttribute,
            DecommissionStatus.SUCCESSFUL
        );
        ClusterState state = ClusterState.builder(new ClusterName("test"))
            .metadata(Metadata.builder().putCustom(DecommissionAttributeMetadata.TYPE, decommissionAttributeMetadata).build())
            .build();

        ActionListener<DeleteDecommissionStateResponse> listener = new ActionListener<>() {
            @Override
            public void onResponse(DeleteDecommissionStateResponse decommissionResponse) {
                DecommissionAttributeMetadata metadata = clusterService.state().metadata().custom(DecommissionAttributeMetadata.TYPE);
                assertNull(metadata);
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                fail("on failure shouldn't have been called");
                countDownLatch.countDown();
            }
        };

        this.decommissionService.deleteDecommissionState(listener);

        // Decommission Attribute should be removed.
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    public void testDeleteDecommissionAttributeClearVotingExclusion() {
        TransportService mockTransportService = Mockito.mock(TransportService.class);
        Mockito.when(mockTransportService.getLocalNode()).thenReturn(Mockito.mock(DiscoveryNode.class));
        DecommissionService decommissionService = new DecommissionService(
            Settings.EMPTY,
            clusterSettings,
            clusterService,
            mockTransportService,
            threadPool,
            allocationService
        );
        decommissionService.startRecommissionAction(Mockito.mock(ActionListener.class));

        ArgumentCaptor<ClearVotingConfigExclusionsRequest> clearVotingConfigExclusionsRequestArgumentCaptor = ArgumentCaptor.forClass(
            ClearVotingConfigExclusionsRequest.class
        );
        Mockito.verify(mockTransportService)
            .sendRequest(
                Mockito.any(DiscoveryNode.class),
                Mockito.anyString(),
                clearVotingConfigExclusionsRequestArgumentCaptor.capture(),
                Mockito.any(TransportResponseHandler.class)
            );

        ClearVotingConfigExclusionsRequest request = clearVotingConfigExclusionsRequestArgumentCaptor.getValue();
        assertFalse(request.getWaitForRemoval());
    }

    public void testClusterUpdateTaskForDeletingDecommission() throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        ActionListener<DeleteDecommissionStateResponse> listener = new ActionListener<>() {
            @Override
            public void onResponse(DeleteDecommissionStateResponse response) {
                assertTrue(response.isAcknowledged());
                assertNull(clusterService.state().metadata().decommissionAttributeMetadata());
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                fail("On Failure shouldn't have been called");
                countDownLatch.countDown();
            }
        };
        decommissionService.deleteDecommissionState(listener);
        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
    }

    private void setWeightedRoutingWeights(Map<String, Double> weights) {
        ClusterState clusterState = clusterService.state();
        WeightedRouting weightedRouting = new WeightedRouting("zone", weights);
        WeightedRoutingMetadata weightedRoutingMetadata = new WeightedRoutingMetadata(weightedRouting);
        Metadata.Builder metadataBuilder = Metadata.builder(clusterState.metadata());
        metadataBuilder.putCustom(WeightedRoutingMetadata.TYPE, weightedRoutingMetadata);
        clusterState = ClusterState.builder(clusterState).metadata(metadataBuilder).build();
        ClusterState.Builder builder = ClusterState.builder(clusterState);
        ClusterServiceUtils.setState(clusterService, builder);
    }

    private ClusterState addDataNodes(ClusterState clusterState, String zone, String... nodeIds) {
        DiscoveryNodes.Builder nodeBuilder = DiscoveryNodes.builder(clusterState.nodes());
        org.opensearch.common.collect.List.of(nodeIds).forEach(nodeId -> nodeBuilder.add(newDataNode(nodeId, singletonMap("zone", zone))));
        clusterState = ClusterState.builder(clusterState).nodes(nodeBuilder).build();
        return clusterState;
    }

    private ClusterState addClusterManagerNodes(ClusterState clusterState, String zone, String... nodeIds) {
        DiscoveryNodes.Builder nodeBuilder = DiscoveryNodes.builder(clusterState.nodes());
        org.opensearch.common.collect.List.of(nodeIds)
            .forEach(nodeId -> nodeBuilder.add(newClusterManagerNode(nodeId, singletonMap("zone", zone))));
        clusterState = ClusterState.builder(clusterState).nodes(nodeBuilder).build();
        return clusterState;
    }

    private ClusterState setLocalNodeAsClusterManagerNode(ClusterState clusterState, String nodeId) {
        DiscoveryNodes.Builder nodeBuilder = DiscoveryNodes.builder(clusterState.nodes());
        nodeBuilder.localNodeId(nodeId);
        nodeBuilder.clusterManagerNodeId(nodeId);
        clusterState = ClusterState.builder(clusterState).nodes(nodeBuilder).build();
        return clusterState;
    }

    private ClusterState setNodesInVotingConfig(ClusterState clusterState, DiscoveryNode... nodes) {
        final CoordinationMetadata.VotingConfiguration votingConfiguration = CoordinationMetadata.VotingConfiguration.of(nodes);

        Metadata.Builder builder = Metadata.builder()
            .coordinationMetadata(
                CoordinationMetadata.builder()
                    .lastAcceptedConfiguration(votingConfiguration)
                    .lastCommittedConfiguration(votingConfiguration)
                    .build()
            );
        clusterState = ClusterState.builder(clusterState).metadata(builder).build();
        return clusterState;
    }

    private static DiscoveryNode newDataNode(String nodeId, Map<String, String> attributes) {
        return new DiscoveryNode(nodeId, buildNewFakeTransportAddress(), attributes, DATA_ROLE, Version.CURRENT);
    }

    private static DiscoveryNode newClusterManagerNode(String nodeId, Map<String, String> attributes) {
        return new DiscoveryNode(nodeId, buildNewFakeTransportAddress(), attributes, CLUSTER_MANAGER_ROLE, Version.CURRENT);
    }

    final private static Set<DiscoveryNodeRole> CLUSTER_MANAGER_ROLE = Collections.unmodifiableSet(
        new HashSet<>(Collections.singletonList(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE))
    );

    final private static Set<DiscoveryNodeRole> DATA_ROLE = Collections.unmodifiableSet(
        new HashSet<>(Collections.singletonList(DiscoveryNodeRole.DATA_ROLE))
    );

    private ClusterState removeNodes(ClusterState clusterState, String... nodeIds) {
        DiscoveryNodes.Builder nodeBuilder = DiscoveryNodes.builder(clusterState.getNodes());
        org.opensearch.common.collect.List.of(nodeIds).forEach(nodeBuilder::remove);
        return allocationService.disassociateDeadNodes(ClusterState.builder(clusterState).nodes(nodeBuilder).build(), false, "test");
    }
}
