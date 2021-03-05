/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.system.partitions.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.zeebe.db.impl.rocksdb.ZeebeRocksDbFactory;
import io.zeebe.logstreams.util.RocksDBWrapper;
import io.zeebe.snapshots.broker.ConstructableSnapshotStore;
import io.zeebe.snapshots.broker.impl.FileBasedSnapshotMetadata;
import io.zeebe.snapshots.broker.impl.FileBasedSnapshotStoreFactory;
import io.zeebe.snapshots.raft.PersistableSnapshot;
import io.zeebe.snapshots.raft.PersistedSnapshot;
import io.zeebe.snapshots.raft.TransientSnapshot;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import org.agrona.collections.MutableLong;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("unchecked")
public final class StateControllerImplTest {

  @Rule public final TemporaryFolder tempFolderRule = new TemporaryFolder();
  @Rule public final AutoCloseableRule autoCloseableRule = new AutoCloseableRule();
  @Rule public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule();

  private final MutableLong exporterPosition = new MutableLong(Long.MAX_VALUE);
  private StateControllerImpl snapshotController;
  private ConstructableSnapshotStore store;

  @Before
  public void setup() throws IOException {
    final var rootDirectory = tempFolderRule.newFolder("state").toPath();

    final var factory = new FileBasedSnapshotStoreFactory(actorSchedulerRule.get(), 1);
    factory.createReceivableSnapshotStore(rootDirectory, 1);
    store = factory.getConstructableSnapshotStore(1);

    snapshotController =
        new StateControllerImpl(
            1,
            ZeebeRocksDbFactory.newFactory(),
            store,
            factory.getReceivableSnapshotStore(1),
            rootDirectory.resolve("runtime"),
            new NoneSnapshotReplication(),
            l ->
                Optional.ofNullable(
                    new IndexedRaftRecord(
                        l, new RaftLogEntry(1, new ApplicationEntry(1, 10, null)), 0, -1)),
            db -> exporterPosition.get());

    autoCloseableRule.manage(snapshotController);
    autoCloseableRule.manage(store);
  }

  @Test
  public void shouldNotTakeSnapshotIfDbIsClosed() {
    // given

    // then
    assertThat(snapshotController.isDbOpened()).isFalse();
    assertThat(snapshotController.takeTransientSnapshot(1)).isEmpty();
  }

  @Test
  public void shouldTakeTempSnapshotWithExporterPosition() {
    // given
    final var snapshotPosition = 1;
    exporterPosition.set(snapshotPosition - 1);
    snapshotController.openDb();

    // when
    final var tmpSnapshot = snapshotController.takeTransientSnapshot(snapshotPosition);
    final var snapshot =
        tmpSnapshot.map(TransientSnapshot::persist).map(ActorFuture::join).orElseThrow();

    // then
    assertThat(snapshot)
        .extracting(PersistedSnapshot::getCompactionBound)
        .isEqualTo(exporterPosition.get());
  }

  @Test
  public void shouldTakeTempSnapshot() throws Exception {
    // given
    final var key = "test";
    final var value = 3;
    final var wrapper = new RocksDBWrapper();
    final var snapshotPosition = 2L;
    exporterPosition.set(snapshotPosition + 1);

    // when
    wrapper.wrap(snapshotController.openDb());
    wrapper.putInt(key, value);
    final var tmpSnapshot = snapshotController.takeTransientSnapshot(snapshotPosition);
    tmpSnapshot.orElseThrow().persist();
    snapshotController.close();
    wrapper.wrap(snapshotController.openDb());

    // then
    assertThat(wrapper.getInt(key)).isEqualTo(value);
  }

  @Test
  public void shouldTakeSnapshotWithExporterPosition() {
    // given
    final var snapshotPosition = 1;
    exporterPosition.set(snapshotPosition - 1);
    snapshotController.openDb();

    // when
    final var snapshot = takeSnapshot(snapshotPosition);

    // then
    assertThat(snapshot.getName()).contains(exporterPosition.toString());
  }

  @Test
  public void shouldTakeSnapshot() throws Exception {
    // given
    final var key = "test";
    final var value = 3;
    final var wrapper = new RocksDBWrapper();
    final var snapshotPosition = 2;
    exporterPosition.set(snapshotPosition + 1);

    // when
    wrapper.wrap(snapshotController.openDb());
    wrapper.putInt(key, value);
    takeSnapshot(snapshotPosition);
    snapshotController.close();
    wrapper.wrap(snapshotController.openDb());

    // then
    assertThat(wrapper.getInt(key)).isEqualTo(value);
  }

  @Test
  public void shouldTakeSnapshotWhenExporterPositionNotChanged() {
    // given
    final var snapshotPosition = 2;
    exporterPosition.set(snapshotPosition - 1);
    snapshotController.openDb();
    final var firstSnapshot =
        snapshotController
            .takeTransientSnapshot(snapshotPosition)
            .map(PersistableSnapshot::persist)
            .map(ActorFuture::join)
            .orElseThrow();

    // when
    final var tmpSnapshot = snapshotController.takeTransientSnapshot(snapshotPosition + 1);
    final var snapshot =
        tmpSnapshot.map(TransientSnapshot::persist).map(ActorFuture::join).orElseThrow();

    // then
    assertThat(snapshot)
        .extracting(PersistedSnapshot::getCompactionBound)
        .isEqualTo(firstSnapshot.getCompactionBound());
    assertThat(snapshot.getId()).isNotEqualTo(firstSnapshot.getId());
    final var newSnapshotId = FileBasedSnapshotMetadata.ofFileName(snapshot.getId()).orElseThrow();
    final var firstSnapshotId =
        FileBasedSnapshotMetadata.ofFileName(firstSnapshot.getId()).orElseThrow();
    assertThat(firstSnapshotId.compareTo(newSnapshotId)).isEqualTo(-1);
  }

  @Test
  public void shouldTakeSnapshotWhenProcessorPositionNotChanged() {
    // given
    final var snapshotPosition = 2;
    exporterPosition.set(snapshotPosition);
    snapshotController.openDb();
    final var firstSnapshot =
        snapshotController
            .takeTransientSnapshot(snapshotPosition)
            .map(PersistableSnapshot::persist)
            .map(ActorFuture::join)
            .orElseThrow();

    // when
    exporterPosition.set(snapshotPosition + 1);
    final var tmpSnapshot = snapshotController.takeTransientSnapshot(snapshotPosition);
    final var snapshot =
        tmpSnapshot.map(TransientSnapshot::persist).map(ActorFuture::join).orElseThrow();

    // then
    assertThat(snapshot)
        .extracting(PersistedSnapshot::getCompactionBound)
        .isEqualTo(firstSnapshot.getCompactionBound());
    assertThat(snapshot.getId()).isNotEqualTo(firstSnapshot.getId());
    final var newSnapshotId = FileBasedSnapshotMetadata.ofFileName(snapshot.getId()).orElseThrow();
    final var firstSnapshotId =
        FileBasedSnapshotMetadata.ofFileName(firstSnapshot.getId()).orElseThrow();
    assertThat(firstSnapshotId.compareTo(newSnapshotId)).isEqualTo(-1);
  }

  @Test
  public void shouldDoNothingIfNoSnapshotsToRecoverFrom() throws Exception {
    // given

    // when
    snapshotController.recover();

    // then
    assertThat(snapshotController.isDbOpened()).isFalse();
  }

  @Test
  public void shouldRemovePreExistingDatabaseOnRecover() throws Exception {
    // given
    final String key = "test";
    final int value = 1;
    final RocksDBWrapper wrapper = new RocksDBWrapper();

    // when
    wrapper.wrap(snapshotController.openDb());
    wrapper.putInt(key, value);
    snapshotController.close();
    snapshotController.recover();
    assertThat(snapshotController.isDbOpened()).isFalse();
    wrapper.wrap(snapshotController.openDb());

    // then
    assertThat(wrapper.mayExist(key)).isFalse();
  }

  @Test
  public void shouldRecoverFromLatestSnapshot() throws Exception {
    // given two snapshots
    final RocksDBWrapper wrapper = new RocksDBWrapper();
    wrapper.wrap(snapshotController.openDb());

    wrapper.putInt("x", 1);
    takeSnapshot(1);

    wrapper.putInt("x", 2);
    takeSnapshot(2);

    wrapper.putInt("x", 3);
    takeSnapshot(3);

    snapshotController.close();

    // when
    snapshotController.recover();
    wrapper.wrap(snapshotController.openDb());

    // then
    assertThat(wrapper.getInt("x")).isEqualTo(3);
  }

  @Test
  public void shouldFailToRecoverIfAllSnapshotsAreCorrupted() throws Exception {
    // given two snapshots
    final RocksDBWrapper wrapper = new RocksDBWrapper();

    wrapper.wrap(snapshotController.openDb());
    wrapper.putInt("x", 1);

    takeSnapshot(1);
    snapshotController.close();
    corruptLatestSnapshot();

    // when/then
    assertThatThrownBy(() -> snapshotController.recover())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to recover from snapshots");
  }

  @Test
  public void shouldGetValidSnapshotCount() {
    // given
    snapshotController.openDb();

    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(0);

    takeSnapshot(1L);
    takeSnapshot(3L);
    takeSnapshot(5L);
    snapshotController.takeTransientSnapshot(6L);

    // when/then
    assertThat(snapshotController.getValidSnapshotsCount()).isEqualTo(1);
  }

  private File takeSnapshot(final long position) {
    final var snapshot = snapshotController.takeTransientSnapshot(position).orElseThrow();
    return snapshot.persist().join().getPath().toFile();
  }

  private void corruptLatestSnapshot() throws IOException {
    final var snapshot = store.getLatestSnapshot();
    final var path = snapshot.orElseThrow().getPath();

    try (final var files = Files.list(path)) {
      final var file =
          files
              .filter(p -> p.toString().endsWith(".sst"))
              .max(Comparator.naturalOrder())
              .orElseThrow();
      Files.write(file, "<--corrupted-->".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
    }
  }
}
