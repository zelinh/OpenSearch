/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.shard;

import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.SegmentInfos;
import org.junit.Assert;
import org.opensearch.OpenSearchException;
import org.opensearch.action.ActionListener;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.concurrent.GatedCloseable;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.CancellableThreads;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.engine.DocIdSeqNoAndSource;
import org.opensearch.index.engine.InternalEngine;
import org.opensearch.index.engine.NRTReplicationEngine;
import org.opensearch.index.engine.NRTReplicationEngineFactory;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.index.replication.OpenSearchIndexLevelReplicationTestCase;
import org.opensearch.index.store.Store;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.indices.replication.CheckpointInfoResponse;
import org.opensearch.indices.replication.GetSegmentFilesResponse;
import org.opensearch.indices.replication.SegmentReplicationSource;
import org.opensearch.indices.replication.SegmentReplicationSourceFactory;
import org.opensearch.indices.replication.SegmentReplicationState;
import org.opensearch.indices.replication.SegmentReplicationTarget;
import org.opensearch.indices.replication.SegmentReplicationTargetService;
import org.opensearch.indices.replication.checkpoint.SegmentReplicationCheckpointPublisher;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.common.CopyState;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

public class SegmentReplicationIndexShardTests extends OpenSearchIndexLevelReplicationTestCase {

    private static final Settings settings = Settings.builder()
        .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
        .build();

    /**
     * Test that latestReplicationCheckpoint returns null only for docrep enabled indices
     */
    public void testReplicationCheckpointNullForDocRep() throws IOException {
        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_REPLICATION_TYPE, "DOCUMENT").put(Settings.EMPTY).build();
        final IndexShard indexShard = newStartedShard(false, indexSettings);
        assertNull(indexShard.getLatestReplicationCheckpoint());
        closeShards(indexShard);
    }

    /**
     * Test that latestReplicationCheckpoint returns ReplicationCheckpoint for segrep enabled indices
     */
    public void testReplicationCheckpointNotNullForSegRep() throws IOException {
        final IndexShard indexShard = newStartedShard(randomBoolean(), settings, new NRTReplicationEngineFactory());
        final ReplicationCheckpoint replicationCheckpoint = indexShard.getLatestReplicationCheckpoint();
        assertNotNull(replicationCheckpoint);
        closeShards(indexShard);
    }

    public void testSegmentReplication_Index_Update_Delete() throws Exception {
        String mappings = "{ \"" + MapperService.SINGLE_MAPPING_NAME + "\": { \"properties\": { \"foo\": { \"type\": \"keyword\"} }}}";
        try (ReplicationGroup shards = createGroup(2, settings, mappings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            final IndexShard primaryShard = shards.getPrimary();

            final int numDocs = randomIntBetween(100, 200);
            for (int i = 0; i < numDocs; i++) {
                shards.index(new IndexRequest(index.getName()).id(String.valueOf(i)).source("{\"foo\": \"bar\"}", XContentType.JSON));
            }

            primaryShard.refresh("Test");
            replicateSegments(primaryShard, shards.getReplicas());

            shards.assertAllEqual(numDocs);

            for (int i = 0; i < numDocs; i++) {
                // randomly update docs.
                if (randomBoolean()) {
                    shards.index(
                        new IndexRequest(index.getName()).id(String.valueOf(i)).source("{ \"foo\" : \"baz\" }", XContentType.JSON)
                    );
                }
            }

            primaryShard.refresh("Test");
            replicateSegments(primaryShard, shards.getReplicas());
            shards.assertAllEqual(numDocs);

            final List<DocIdSeqNoAndSource> docs = getDocIdAndSeqNos(primaryShard);
            for (IndexShard shard : shards.getReplicas()) {
                assertEquals(getDocIdAndSeqNos(shard), docs);
            }
            for (int i = 0; i < numDocs; i++) {
                // randomly delete.
                if (randomBoolean()) {
                    shards.delete(new DeleteRequest(index.getName()).id(String.valueOf(i)));
                }
            }
            primaryShard.refresh("Test");
            replicateSegments(primaryShard, shards.getReplicas());
            final List<DocIdSeqNoAndSource> docsAfterDelete = getDocIdAndSeqNos(primaryShard);
            for (IndexShard shard : shards.getReplicas()) {
                assertEquals(getDocIdAndSeqNos(shard), docsAfterDelete);
            }
        }
    }

    public void testIgnoreShardIdle() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            final IndexShard primary = shards.getPrimary();
            final IndexShard replica = shards.getReplicas().get(0);

            final int numDocs = shards.indexDocs(randomInt(10));
            primary.refresh("test");
            replicateSegments(primary, shards.getReplicas());
            shards.assertAllEqual(numDocs);

            primary.scheduledRefresh();
            replica.scheduledRefresh();

            primary.awaitShardSearchActive(b -> assertFalse("A new RefreshListener should not be registered", b));
            replica.awaitShardSearchActive(b -> assertFalse("A new RefreshListener should not be registered", b));

            // Update the search_idle setting, this will put both shards into search idle.
            Settings updatedSettings = Settings.builder()
                .put(settings)
                .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.ZERO)
                .build();
            primary.indexSettings().getScopedSettings().applySettings(updatedSettings);
            replica.indexSettings().getScopedSettings().applySettings(updatedSettings);

            primary.scheduledRefresh();
            replica.scheduledRefresh();

            // Shards without segrep will register a new RefreshListener on the engine and return true when registered,
            // assert with segrep enabled that awaitShardSearchActive does not register a listener.
            primary.awaitShardSearchActive(b -> assertFalse("A new RefreshListener should not be registered", b));
            replica.awaitShardSearchActive(b -> assertFalse("A new RefreshListener should not be registered", b));
        }
    }

    /**
     * here we are starting a new primary shard in PrimaryMode and testing if the shard publishes checkpoint after refresh.
     */
    public void testPublishCheckpointOnPrimaryMode() throws IOException {
        final SegmentReplicationCheckpointPublisher mock = mock(SegmentReplicationCheckpointPublisher.class);
        IndexShard shard = newStartedShard(true);
        CheckpointRefreshListener refreshListener = new CheckpointRefreshListener(shard, mock);
        refreshListener.afterRefresh(true);

        // verify checkpoint is published
        verify(mock, times(1)).publish(any());
        closeShards(shard);
    }

    /**
     * here we are starting a new primary shard in PrimaryMode initially and starting relocation handoff. Later we complete relocation handoff then shard is no longer
     * in PrimaryMode, and we test if the shard does not publish checkpoint after refresh.
     */
    public void testPublishCheckpointAfterRelocationHandOff() throws IOException {
        final SegmentReplicationCheckpointPublisher mock = mock(SegmentReplicationCheckpointPublisher.class);
        IndexShard shard = newStartedShard(true);
        CheckpointRefreshListener refreshListener = new CheckpointRefreshListener(shard, mock);
        String id = shard.routingEntry().allocationId().getId();

        // Starting relocation handoff
        shard.getReplicationTracker().startRelocationHandoff(id);

        // Completing relocation handoff
        shard.getReplicationTracker().completeRelocationHandoff();
        refreshListener.afterRefresh(true);

        // verify checkpoint is not published
        verify(mock, times(0)).publish(any());
        closeShards(shard);
    }

    /**
     * here we are starting a new primary shard and testing that we don't process a checkpoint on a shard when it's shard routing is primary.
     */
    public void testRejectCheckpointOnShardRoutingPrimary() throws IOException {
        IndexShard primaryShard = newStartedShard(true);
        SegmentReplicationTargetService sut;
        sut = prepareForReplication(primaryShard);
        SegmentReplicationTargetService spy = spy(sut);

        // Starting a new shard in PrimaryMode and shard routing primary.
        IndexShard spyShard = spy(primaryShard);
        String id = primaryShard.routingEntry().allocationId().getId();

        // Starting relocation handoff
        primaryShard.getReplicationTracker().startRelocationHandoff(id);

        // Completing relocation handoff.
        primaryShard.getReplicationTracker().completeRelocationHandoff();

        // Assert that primary shard is no longer in Primary Mode and shard routing is still Primary
        assertEquals(false, primaryShard.getReplicationTracker().isPrimaryMode());
        assertEquals(true, primaryShard.routingEntry().primary());

        spy.onNewCheckpoint(new ReplicationCheckpoint(primaryShard.shardId(), 0L, 0L, 0L, 0L), spyShard);

        // Verify that checkpoint is not processed as shard routing is primary.
        verify(spy, times(0)).startReplication(any(), any(), any());
        closeShards(primaryShard);
    }

    public void testReplicaReceivesGenIncrease() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            final IndexShard primary = shards.getPrimary();
            final IndexShard replica = shards.getReplicas().get(0);
            final int numDocs = randomIntBetween(10, 100);
            shards.indexDocs(numDocs);
            assertEquals(numDocs, primary.translogStats().estimatedNumberOfOperations());
            assertEquals(numDocs, replica.translogStats().estimatedNumberOfOperations());
            assertEquals(numDocs, primary.translogStats().getUncommittedOperations());
            assertEquals(numDocs, replica.translogStats().getUncommittedOperations());
            flushShard(primary, true);
            replicateSegments(primary, shards.getReplicas());
            assertEquals(0, primary.translogStats().estimatedNumberOfOperations());
            assertEquals(0, replica.translogStats().estimatedNumberOfOperations());
            assertEquals(0, primary.translogStats().getUncommittedOperations());
            assertEquals(0, replica.translogStats().getUncommittedOperations());

            final int additionalDocs = shards.indexDocs(randomIntBetween(numDocs + 1, numDocs + 10));

            final int totalDocs = numDocs + additionalDocs;
            primary.refresh("test");
            replicateSegments(primary, shards.getReplicas());
            assertEquals(additionalDocs, primary.translogStats().estimatedNumberOfOperations());
            assertEquals(additionalDocs, replica.translogStats().estimatedNumberOfOperations());
            assertEquals(additionalDocs, primary.translogStats().getUncommittedOperations());
            assertEquals(additionalDocs, replica.translogStats().getUncommittedOperations());
            flushShard(primary, true);
            replicateSegments(primary, shards.getReplicas());

            assertEqualCommittedSegments(primary, replica);
            assertDocCount(primary, totalDocs);
            assertDocCount(replica, totalDocs);
            assertEquals(0, primary.translogStats().estimatedNumberOfOperations());
            assertEquals(0, replica.translogStats().estimatedNumberOfOperations());
            assertEquals(0, primary.translogStats().getUncommittedOperations());
            assertEquals(0, replica.translogStats().getUncommittedOperations());
        }
    }

    public void testReplicaReceivesLowerGeneration() throws Exception {
        // when a replica gets incoming segments that are lower than what it currently has on disk.

        // start 3 nodes Gens: P [2], R [2], R[2]
        // index some docs and flush twice, push to only 1 replica.
        // State Gens: P [4], R-1 [3], R-2 [2]
        // Promote R-2 as the new primary and demote the old primary.
        // State Gens: R[4], R-1 [3], P [4] - *commit on close of NRTEngine, xlog replayed and commit made.
        // index docs on new primary and flush
        // replicate to all.
        // Expected result: State Gens: P[4], R-1 [4], R-2 [4]
        try (ReplicationGroup shards = createGroup(2, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            final IndexShard primary = shards.getPrimary();
            final IndexShard replica_1 = shards.getReplicas().get(0);
            final IndexShard replica_2 = shards.getReplicas().get(1);
            int numDocs = randomIntBetween(10, 100);
            shards.indexDocs(numDocs);
            flushShard(primary, false);
            replicateSegments(primary, List.of(replica_1));
            numDocs = randomIntBetween(numDocs + 1, numDocs + 10);
            shards.indexDocs(numDocs);
            flushShard(primary, false);
            assertLatestCommitGen(4, primary);
            replicateSegments(primary, List.of(replica_1));

            assertEqualCommittedSegments(primary, replica_1);
            assertLatestCommitGen(4, primary, replica_1);
            assertLatestCommitGen(2, replica_2);

            shards.promoteReplicaToPrimary(replica_2).get();
            primary.close("demoted", false);
            primary.store().close();
            IndexShard oldPrimary = shards.addReplicaWithExistingPath(primary.shardPath(), primary.routingEntry().currentNodeId());
            shards.recoverReplica(oldPrimary);
            assertLatestCommitGen(4, oldPrimary);
            assertEqualCommittedSegments(oldPrimary, replica_1);

            assertLatestCommitGen(4, replica_2);

            numDocs = randomIntBetween(numDocs + 1, numDocs + 10);
            shards.indexDocs(numDocs);
            flushShard(replica_2, false);
            replicateSegments(replica_2, shards.getReplicas());
            assertEqualCommittedSegments(replica_2, oldPrimary, replica_1);
        }
    }

    public void testReplicaRestarts() throws Exception {
        try (ReplicationGroup shards = createGroup(3, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            IndexShard primary = shards.getPrimary();
            // 1. Create ops that are in the index and xlog of both shards but not yet part of a commit point.
            final int numDocs = shards.indexDocs(randomInt(10));

            // refresh and copy the segments over.
            if (randomBoolean()) {
                flushShard(primary);
            }
            primary.refresh("Test");
            replicateSegments(primary, shards.getReplicas());

            // at this point both shards should have numDocs persisted and searchable.
            assertDocCounts(primary, numDocs, numDocs);
            for (IndexShard shard : shards.getReplicas()) {
                assertDocCounts(shard, numDocs, numDocs);
            }

            final int i1 = randomInt(5);
            for (int i = 0; i < i1; i++) {
                shards.indexDocs(randomInt(10));

                // randomly resetart a replica
                final IndexShard replicaToRestart = getRandomReplica(shards);
                replicaToRestart.close("restart", false);
                replicaToRestart.store().close();
                shards.removeReplica(replicaToRestart);
                final IndexShard newReplica = shards.addReplicaWithExistingPath(
                    replicaToRestart.shardPath(),
                    replicaToRestart.routingEntry().currentNodeId()
                );
                shards.recoverReplica(newReplica);

                // refresh and push segments to our other replicas.
                if (randomBoolean()) {
                    failAndPromoteRandomReplica(shards);
                }
                flushShard(shards.getPrimary());
                replicateSegments(shards.getPrimary(), shards.getReplicas());
            }
            primary = shards.getPrimary();

            // refresh and push segments to our other replica.
            flushShard(primary);
            replicateSegments(primary, shards.getReplicas());

            for (IndexShard shard : shards) {
                assertConsistentHistoryBetweenTranslogAndLucene(shard);
            }
            final List<DocIdSeqNoAndSource> docsAfterReplication = getDocIdAndSeqNos(shards.getPrimary());
            for (IndexShard shard : shards.getReplicas()) {
                assertThat(shard.routingEntry().toString(), getDocIdAndSeqNos(shard), equalTo(docsAfterReplication));
            }
        }
    }

    public void testNRTReplicaPromotedAsPrimary() throws Exception {
        try (ReplicationGroup shards = createGroup(2, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            IndexShard oldPrimary = shards.getPrimary();
            final IndexShard nextPrimary = shards.getReplicas().get(0);
            final IndexShard replica = shards.getReplicas().get(1);

            // 1. Create ops that are in the index and xlog of both shards but not yet part of a commit point.
            final int numDocs = shards.indexDocs(randomInt(10));

            // refresh and copy the segments over.
            oldPrimary.refresh("Test");
            replicateSegments(oldPrimary, shards.getReplicas());

            // at this point both shards should have numDocs persisted and searchable.
            assertDocCounts(oldPrimary, numDocs, numDocs);
            for (IndexShard shard : shards.getReplicas()) {
                assertDocCounts(shard, numDocs, numDocs);
            }

            // 2. Create ops that are in the replica's xlog, not in the index.
            // index some more into both but don't replicate. replica will have only numDocs searchable, but should have totalDocs
            // persisted.
            final int additonalDocs = shards.indexDocs(randomInt(10));
            final int totalDocs = numDocs + additonalDocs;

            assertDocCounts(oldPrimary, totalDocs, totalDocs);
            for (IndexShard shard : shards.getReplicas()) {
                assertDocCounts(shard, totalDocs, numDocs);
            }
            assertEquals(additonalDocs, nextPrimary.translogStats().estimatedNumberOfOperations());
            assertEquals(additonalDocs, replica.translogStats().estimatedNumberOfOperations());
            assertEquals(additonalDocs, nextPrimary.translogStats().getUncommittedOperations());
            assertEquals(additonalDocs, replica.translogStats().getUncommittedOperations());

            // promote the replica
            shards.syncGlobalCheckpoint();
            shards.promoteReplicaToPrimary(nextPrimary);

            // close and start the oldPrimary as a replica.
            oldPrimary.close("demoted", false);
            oldPrimary.store().close();
            oldPrimary = shards.addReplicaWithExistingPath(oldPrimary.shardPath(), oldPrimary.routingEntry().currentNodeId());
            shards.recoverReplica(oldPrimary);

            assertEquals(NRTReplicationEngine.class, oldPrimary.getEngine().getClass());
            assertEquals(InternalEngine.class, nextPrimary.getEngine().getClass());
            assertDocCounts(nextPrimary, totalDocs, totalDocs);
            assertEquals(0, nextPrimary.translogStats().estimatedNumberOfOperations());

            // refresh and push segments to our other replica.
            nextPrimary.refresh("test");
            replicateSegments(nextPrimary, asList(replica));

            for (IndexShard shard : shards) {
                assertConsistentHistoryBetweenTranslogAndLucene(shard);
            }
            final List<DocIdSeqNoAndSource> docsAfterRecovery = getDocIdAndSeqNos(shards.getPrimary());
            for (IndexShard shard : shards.getReplicas()) {
                assertThat(shard.routingEntry().toString(), getDocIdAndSeqNos(shard), equalTo(docsAfterRecovery));
            }
        }
    }

    public void testReplicaPromotedWhileReplicating() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            final IndexShard oldPrimary = shards.getPrimary();
            final IndexShard nextPrimary = shards.getReplicas().get(0);

            final int numDocs = shards.indexDocs(randomInt(10));
            oldPrimary.refresh("Test");
            shards.syncGlobalCheckpoint();

            final SegmentReplicationSourceFactory sourceFactory = mock(SegmentReplicationSourceFactory.class);
            final SegmentReplicationTargetService targetService = newTargetService(sourceFactory);
            SegmentReplicationSource source = new SegmentReplicationSource() {
                @Override
                public void getCheckpointMetadata(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    ActionListener<CheckpointInfoResponse> listener
                ) {
                    resolveCheckpointInfoResponseListener(listener, oldPrimary);
                    ShardRouting oldRouting = nextPrimary.shardRouting;
                    try {
                        shards.promoteReplicaToPrimary(nextPrimary);
                    } catch (IOException e) {
                        Assert.fail("Promotion should not fail");
                    }
                    targetService.shardRoutingChanged(nextPrimary, oldRouting, nextPrimary.shardRouting);
                }

                @Override
                public void getSegmentFiles(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    List<StoreFileMetadata> filesToFetch,
                    Store store,
                    ActionListener<GetSegmentFilesResponse> listener
                ) {
                    listener.onResponse(new GetSegmentFilesResponse(Collections.emptyList()));
                }
            };
            when(sourceFactory.get(any())).thenReturn(source);
            startReplicationAndAssertCancellation(nextPrimary, targetService);
            // wait for replica to finish being promoted, and assert doc counts.
            final CountDownLatch latch = new CountDownLatch(1);
            nextPrimary.acquirePrimaryOperationPermit(new ActionListener<>() {
                @Override
                public void onResponse(Releasable releasable) {
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    throw new AssertionError(e);
                }
            }, ThreadPool.Names.GENERIC, "");
            latch.await();
            assertEquals(nextPrimary.getEngine().getClass(), InternalEngine.class);
            nextPrimary.refresh("test");

            oldPrimary.close("demoted", false);
            oldPrimary.store().close();
            IndexShard newReplica = shards.addReplicaWithExistingPath(oldPrimary.shardPath(), oldPrimary.routingEntry().currentNodeId());
            shards.recoverReplica(newReplica);

            assertDocCount(nextPrimary, numDocs);
            assertDocCount(newReplica, numDocs);

            nextPrimary.refresh("test");
            replicateSegments(nextPrimary, shards.getReplicas());
            final List<DocIdSeqNoAndSource> docsAfterRecovery = getDocIdAndSeqNos(shards.getPrimary());
            for (IndexShard shard : shards.getReplicas()) {
                assertThat(shard.routingEntry().toString(), getDocIdAndSeqNos(shard), equalTo(docsAfterRecovery));
            }
        }
    }

    public void testReplicaClosesWhileReplicating_AfterGetCheckpoint() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            IndexShard primary = shards.getPrimary();
            final IndexShard replica = shards.getReplicas().get(0);

            final int numDocs = shards.indexDocs(randomInt(10));
            primary.refresh("Test");

            final SegmentReplicationSourceFactory sourceFactory = mock(SegmentReplicationSourceFactory.class);
            final SegmentReplicationTargetService targetService = newTargetService(sourceFactory);
            SegmentReplicationSource source = new SegmentReplicationSource() {
                @Override
                public void getCheckpointMetadata(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    ActionListener<CheckpointInfoResponse> listener
                ) {
                    // trigger a cancellation by closing the replica.
                    targetService.beforeIndexShardClosed(replica.shardId, replica, Settings.EMPTY);
                    resolveCheckpointInfoResponseListener(listener, primary);
                }

                @Override
                public void getSegmentFiles(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    List<StoreFileMetadata> filesToFetch,
                    Store store,
                    ActionListener<GetSegmentFilesResponse> listener
                ) {
                    Assert.fail("Should not be reached");
                }
            };
            when(sourceFactory.get(any())).thenReturn(source);
            startReplicationAndAssertCancellation(replica, targetService);

            shards.removeReplica(replica);
            closeShards(replica);
        }
    }

    public void testReplicaClosesWhileReplicating_AfterGetSegmentFiles() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            IndexShard primary = shards.getPrimary();
            final IndexShard replica = shards.getReplicas().get(0);

            final int numDocs = shards.indexDocs(randomInt(10));
            primary.refresh("Test");

            final SegmentReplicationSourceFactory sourceFactory = mock(SegmentReplicationSourceFactory.class);
            final SegmentReplicationTargetService targetService = newTargetService(sourceFactory);
            SegmentReplicationSource source = new SegmentReplicationSource() {
                @Override
                public void getCheckpointMetadata(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    ActionListener<CheckpointInfoResponse> listener
                ) {
                    resolveCheckpointInfoResponseListener(listener, primary);
                }

                @Override
                public void getSegmentFiles(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    List<StoreFileMetadata> filesToFetch,
                    Store store,
                    ActionListener<GetSegmentFilesResponse> listener
                ) {
                    // randomly resolve the listener, indicating the source has resolved.
                    listener.onResponse(new GetSegmentFilesResponse(Collections.emptyList()));
                    targetService.beforeIndexShardClosed(replica.shardId, replica, Settings.EMPTY);
                }
            };
            when(sourceFactory.get(any())).thenReturn(source);
            startReplicationAndAssertCancellation(replica, targetService);

            shards.removeReplica(replica);
            closeShards(replica);
        }
    }

    public void testPrimaryCancelsExecution() throws Exception {
        try (ReplicationGroup shards = createGroup(1, settings, new NRTReplicationEngineFactory())) {
            shards.startAll();
            IndexShard primary = shards.getPrimary();
            final IndexShard replica = shards.getReplicas().get(0);

            final int numDocs = shards.indexDocs(randomInt(10));
            primary.refresh("Test");

            final SegmentReplicationSourceFactory sourceFactory = mock(SegmentReplicationSourceFactory.class);
            final SegmentReplicationTargetService targetService = newTargetService(sourceFactory);
            SegmentReplicationSource source = new SegmentReplicationSource() {
                @Override
                public void getCheckpointMetadata(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    ActionListener<CheckpointInfoResponse> listener
                ) {
                    listener.onFailure(new CancellableThreads.ExecutionCancelledException("Cancelled"));
                }

                @Override
                public void getSegmentFiles(
                    long replicationId,
                    ReplicationCheckpoint checkpoint,
                    List<StoreFileMetadata> filesToFetch,
                    Store store,
                    ActionListener<GetSegmentFilesResponse> listener
                ) {}
            };
            when(sourceFactory.get(any())).thenReturn(source);
            startReplicationAndAssertCancellation(replica, targetService);

            shards.removeReplica(replica);
            closeShards(replica);
        }
    }

    private SegmentReplicationTargetService newTargetService(SegmentReplicationSourceFactory sourceFactory) {
        return new SegmentReplicationTargetService(
            threadPool,
            new RecoverySettings(Settings.EMPTY, new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)),
            mock(TransportService.class),
            sourceFactory
        );
    }

    /**
     * Assert persisted and searchable doc counts.  This method should not be used while docs are concurrently indexed because
     * it asserts point in time seqNos are relative to the doc counts.
     */
    private void assertDocCounts(IndexShard indexShard, int expectedPersistedDocCount, int expectedSearchableDocCount) throws IOException {
        assertDocCount(indexShard, expectedSearchableDocCount);
        // assigned seqNos start at 0, so assert max & local seqNos are 1 less than our persisted doc count.
        assertEquals(expectedPersistedDocCount - 1, indexShard.seqNoStats().getMaxSeqNo());
        assertEquals(expectedPersistedDocCount - 1, indexShard.seqNoStats().getLocalCheckpoint());
        // processed cp should be 1 less than our searchable doc count.
        assertEquals(expectedSearchableDocCount - 1, indexShard.getProcessedLocalCheckpoint());
    }

    private void resolveCheckpointInfoResponseListener(ActionListener<CheckpointInfoResponse> listener, IndexShard primary) {
        try {
            final CopyState copyState = new CopyState(ReplicationCheckpoint.empty(primary.shardId), primary);
            listener.onResponse(
                new CheckpointInfoResponse(copyState.getCheckpoint(), copyState.getMetadataMap(), copyState.getInfosBytes())
            );
        } catch (IOException e) {
            logger.error("Unexpected error computing CopyState", e);
            Assert.fail("Failed to compute copyState");
        }
    }

    private void startReplicationAndAssertCancellation(IndexShard replica, SegmentReplicationTargetService targetService)
        throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final SegmentReplicationTarget target = targetService.startReplication(
            ReplicationCheckpoint.empty(replica.shardId),
            replica,
            new SegmentReplicationTargetService.SegmentReplicationListener() {
                @Override
                public void onReplicationDone(SegmentReplicationState state) {
                    Assert.fail("Replication should not complete");
                }

                @Override
                public void onReplicationFailure(SegmentReplicationState state, OpenSearchException e, boolean sendShardFailure) {
                    assertTrue(e instanceof CancellableThreads.ExecutionCancelledException);
                    assertFalse(sendShardFailure);
                    assertEquals(SegmentReplicationState.Stage.CANCELLED, state.getStage());
                    latch.countDown();
                }
            }
        );

        latch.await(2, TimeUnit.SECONDS);
        assertEquals("Should have resolved listener with failure", 0, latch.getCount());
        assertNull(targetService.get(target.getId()));
    }

    private IndexShard getRandomReplica(ReplicationGroup shards) {
        return shards.getReplicas().get(randomInt(shards.getReplicas().size() - 1));
    }

    private IndexShard failAndPromoteRandomReplica(ReplicationGroup shards) throws IOException {
        IndexShard primary = shards.getPrimary();
        final IndexShard newPrimary = getRandomReplica(shards);
        shards.promoteReplicaToPrimary(newPrimary);
        primary.close("demoted", true);
        primary.store().close();
        primary = shards.addReplicaWithExistingPath(primary.shardPath(), primary.routingEntry().currentNodeId());
        shards.recoverReplica(primary);
        return newPrimary;
    }

    private void assertLatestCommitGen(long expected, IndexShard... shards) throws IOException {
        for (IndexShard indexShard : shards) {
            try (final GatedCloseable<IndexCommit> commit = indexShard.acquireLastIndexCommit(false)) {
                assertEquals(expected, commit.get().getGeneration());
            }
        }
    }

    private void assertEqualCommittedSegments(IndexShard primary, IndexShard... replicas) throws IOException {
        for (IndexShard replica : replicas) {
            final SegmentInfos replicaInfos = replica.store().readLastCommittedSegmentsInfo();
            final SegmentInfos primaryInfos = primary.store().readLastCommittedSegmentsInfo();
            final Map<String, StoreFileMetadata> latestReplicaMetadata = replica.store().getSegmentMetadataMap(replicaInfos);
            final Map<String, StoreFileMetadata> latestPrimaryMetadata = primary.store().getSegmentMetadataMap(primaryInfos);
            final Store.RecoveryDiff diff = Store.segmentReplicationDiff(latestPrimaryMetadata, latestReplicaMetadata);
            assertTrue(diff.different.isEmpty());
            assertTrue(diff.missing.isEmpty());
        }
    }
}
