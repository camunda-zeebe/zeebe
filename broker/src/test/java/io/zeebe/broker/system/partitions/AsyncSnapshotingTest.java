/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.system.partitions;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.zeebe.broker.system.partitions.impl.AsyncSnapshotDirector;
import io.zeebe.broker.system.partitions.impl.NoneSnapshotReplication;
import io.zeebe.broker.system.partitions.impl.StateControllerImpl;
import io.zeebe.db.impl.rocksdb.ZeebeRocksDbFactory;
import io.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.snapshots.broker.ConstructableSnapshotStore;
import io.zeebe.snapshots.broker.impl.FileBasedSnapshotStoreFactory;
import io.zeebe.snapshots.raft.PersistedSnapshot;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.clock.ControlledActorClock;
import io.zeebe.util.sched.future.CompletableActorFuture;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

public final class AsyncSnapshotingTest {

  private final TemporaryFolder tempFolderRule = new TemporaryFolder();
  private final AutoCloseableRule autoCloseableRule = new AutoCloseableRule();

  private final ControlledActorClock clock = new ControlledActorClock();
  private final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule(clock);

  @Rule
  public final RuleChain chain =
      RuleChain.outerRule(autoCloseableRule).around(tempFolderRule).around(actorSchedulerRule);

  private StateControllerImpl snapshotController;
  private LogStream logStream;
  private AsyncSnapshotDirector asyncSnapshotDirector;
  private StreamProcessor mockStreamProcessor;
  private List<ActorCondition> conditionList;
  private ConstructableSnapshotStore persistedSnapshotStore;

  @Before
  public void setup() throws IOException {
    final var rootDirectory = tempFolderRule.getRoot().toPath();
    final var factory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 1);
    final int partitionId = 1;
    factory.createReceivableSnapshotStore(rootDirectory, partitionId);
    persistedSnapshotStore = factory.getConstructableSnapshotStore(partitionId);

    snapshotController =
        new StateControllerImpl(
            1,
            ZeebeRocksDbFactory.newFactory(),
            persistedSnapshotStore,
            factory.getReceivableSnapshotStore(partitionId),
            rootDirectory.resolve("runtime"),
            new NoneSnapshotReplication(),
            l ->
                Optional.of(
                    new IndexedRaftRecord(
                        l + 100, new RaftLogEntry(1, new ApplicationEntry(1, 10, null)), 0, -1)),
            db -> Long.MAX_VALUE);

    snapshotController.openDb();
    autoCloseableRule.manage(snapshotController);
    autoCloseableRule.manage(persistedSnapshotStore);
    snapshotController = spy(snapshotController);

    logStream = mock(LogStream.class);
    when(logStream.getCommitPositionAsync()).thenReturn(CompletableActorFuture.completed(25L));
    conditionList = new ArrayList<>();
    doAnswer(
            invocationOnMock -> {
              final Object[] arguments = invocationOnMock.getArguments();
              conditionList.add((ActorCondition) arguments[0]);
              return null;
            })
        .when(logStream)
        .registerOnCommitPositionUpdatedCondition(any());

    final ActorScheduler actorScheduler = actorSchedulerRule.get();
    createStreamProcessorControllerMock();
    createAsyncSnapshotDirector(actorScheduler);
  }

  private void setCommitPosition(final long commitPosition) {
    when(logStream.getCommitPositionAsync())
        .thenReturn(CompletableActorFuture.completed(commitPosition));
    conditionList.forEach(ActorCondition::signal);
  }

  private void createStreamProcessorControllerMock() {
    mockStreamProcessor = mock(StreamProcessor.class);

    when(mockStreamProcessor.getLastProcessedPositionAsync())
        .thenReturn(CompletableActorFuture.completed(0L))
        .thenReturn(CompletableActorFuture.completed(25L))
        .thenReturn(CompletableActorFuture.completed(32L));

    when(mockStreamProcessor.getLastWrittenPositionAsync())
        .thenReturn(CompletableActorFuture.completed(99L), CompletableActorFuture.completed(100L));
  }

  private void createAsyncSnapshotDirector(final ActorScheduler actorScheduler) {
    asyncSnapshotDirector =
        new AsyncSnapshotDirector(
            0, mockStreamProcessor, snapshotController, logStream, Duration.ofMinutes(1));
    actorScheduler.submitActor(asyncSnapshotDirector).join();
  }

  @Test
  public void shouldValidSnapshotWhenCommitPositionGreaterEquals() {
    // given
    clock.addTime(Duration.ofMinutes(1));

    // when
    setCommitPosition(100L);

    // then
    waitUntil(() -> snapshotController.getValidSnapshotsCount() == 1);
    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(1);
  }

  @Test
  public void shouldTakeSnapshotsOneByOne() {
    // given
    clock.addTime(Duration.ofMinutes(1));
    setCommitPosition(99L);
    waitUntil(() -> snapshotController.getValidSnapshotsCount() == 1);

    // when
    clock.addTime(Duration.ofMinutes(1));
    setCommitPosition(100L);

    // then
    awaitSnapshot(100);
    assertThat(persistedSnapshotStore.getLatestSnapshot())
        .get()
        .extracting(PersistedSnapshot::getIndex)
        .isEqualTo(100L);
  }

  private void awaitSnapshot(final int index) {
    waitUntil(
        () -> {
          final var optSnapshot = persistedSnapshotStore.getLatestSnapshot();
          if (optSnapshot.isPresent()) {
            final var snapshot = optSnapshot.get();
            return snapshot.getIndex() == index;
          }
          return false;
        });
  }

  @Test
  public void shouldSucceedToTakeSnapshotOnNextIntervalWhenLastWritePosRetrievingFailed() {
    // given
    final long lastProcessedPosition = 25L;
    final long lastWrittenPosition = 26L;
    final long commitPosition = 100L;

    when(mockStreamProcessor.getLastProcessedPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastProcessedPosition));
    when(mockStreamProcessor.getLastWrittenPositionAsync())
        .thenReturn(
            CompletableActorFuture.completedExceptionally(
                new RuntimeException("getLastWrittenPositionAsync fails")));
    setCommitPosition(commitPosition);
    clock.addTime(Duration.ofMinutes(1));
    verify(mockStreamProcessor, timeout(5000).times(1)).getLastWrittenPositionAsync();

    // when
    when(mockStreamProcessor.getLastWrittenPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastWrittenPosition));
    clock.addTime(Duration.ofMinutes(1));

    // then
    waitUntil(() -> snapshotController.getValidSnapshotsCount() == 1);
    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(1);
  }

  @Test
  public void shouldSucceedToTakeSnapshotOnNextIntervalWhenLastProcessedPosRetrievingFailed() {
    // given
    final long lastProcessedPosition = 25L;
    final long lastWrittenPosition = 26L;
    final long commitPosition = 100L;

    when(mockStreamProcessor.getLastProcessedPositionAsync())
        .thenReturn(
            CompletableActorFuture.completedExceptionally(
                new RuntimeException("getLastProcessedPositionAsync fails")));
    when(mockStreamProcessor.getLastWrittenPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastWrittenPosition));
    setCommitPosition(commitPosition);
    clock.addTime(Duration.ofMinutes(1));
    verify(mockStreamProcessor, timeout(5000).times(1)).getLastProcessedPositionAsync();

    // when
    when(mockStreamProcessor.getLastProcessedPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastProcessedPosition));
    clock.addTime(Duration.ofMinutes(1));

    // then
    waitUntil(() -> snapshotController.getValidSnapshotsCount() == 1);
    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(1);
  }

  @Test
  public void shouldSucceedToTakeSnapshotOnNextIntervalWhenCommitPositionRetrievingFailed() {
    // given
    final long lastProcessedPosition = 25L;
    final long lastWrittenPosition = 26L;

    when(mockStreamProcessor.getLastProcessedPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastProcessedPosition));
    when(mockStreamProcessor.getLastWrittenPositionAsync())
        .thenReturn(CompletableActorFuture.completed(lastWrittenPosition));
    when(logStream.getCommitPositionAsync())
        .thenReturn(
            CompletableActorFuture.completedExceptionally(
                new RuntimeException("getCommitPositionAsync fails")));
    clock.addTime(Duration.ofMinutes(1));
    verify(logStream, timeout(5000).times(1)).getCommitPositionAsync();

    // when

    setCommitPosition(100L);
    clock.addTime(Duration.ofMinutes(1));

    // then
    waitUntil(() -> snapshotController.getValidSnapshotsCount() == 1);
    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(1);
  }
}
