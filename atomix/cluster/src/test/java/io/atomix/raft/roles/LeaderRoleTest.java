/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.raft.RaftException.NoLeader;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.metrics.RaftReplicationMetrics;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.zeebe.ValidationResult;
import io.atomix.raft.zeebe.ZeebeLogAppender.AppendListener;
import io.atomix.raft.zeebe.util.TestAppender;
import io.atomix.storage.StorageException;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.zeebe.snapshots.raft.ReceivableSnapshotStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class LeaderRoleTest {

  private LeaderRole leaderRole;
  private RaftContext context;
  private RaftLog log;
  private RaftLogReader reader;

  @Before
  public void setup() {
    context = Mockito.mock(RaftContext.class);

    when(context.getName()).thenReturn("leader");
    when(context.getElectionTimeout()).thenReturn(Duration.ofMillis(100));
    when(context.getHeartbeatInterval()).thenReturn(Duration.ofMillis(100));
    when(context.getReplicationMetrics()).thenReturn(mock(RaftReplicationMetrics.class));

    final SingleThreadContext threadContext = new SingleThreadContext("leader");
    when(context.getThreadContext()).thenReturn(threadContext);

    log = mock(RaftLog.class);
    when(log.getLastIndex()).thenReturn(1L);
    when(log.append(any(RaftLogEntry.class)))
        .then(
            i -> {
              final ApplicationEntry applicationEntry = i.getArgument(0);
              return new IndexedRaftRecord(1, new RaftLogEntry(1, applicationEntry), 45, -1);
            });
    when(context.getLog()).thenReturn(log);

    final ReceivableSnapshotStore persistedSnapshotStore = mock(ReceivableSnapshotStore.class);
    when(context.getPersistedSnapshotStore()).thenReturn(persistedSnapshotStore);
    when(context.getEntryValidator()).thenReturn((a, b) -> ValidationResult.success());
    when(context.getStorage()).thenReturn(RaftStorage.builder().withMaxSegmentSize(1024).build());

    leaderRole = new LeaderRole(context);
    // since we mock RaftContext we should simulate leader close on transition
    doAnswer(i -> leaderRole.stop().join()).when(context).transition(Role.FOLLOWER);
    when(context.getMembershipService()).thenReturn(mock(ClusterMembershipService.class));

    reader = mock(RaftLogReader.class);
    when(context.getLogReader()).thenReturn(reader);
  }

  @Test
  public void shouldAppendEntry() throws InterruptedException {
    // given
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWrite(final IndexedRaftRecord indexed) {
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    assertThat(latch.getCount()).isZero();
  }

  @Test
  public void shouldRetryAppendEntryOnIOException() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class)))
        .thenThrow(new StorageException(new IOException()))
        .thenThrow(new StorageException(new IOException()))
        .then(
            i -> {
              final ApplicationEntry applicationEntry = i.getArgument(0);
              return new IndexedRaftRecord(1, new RaftLogEntry(1, applicationEntry), 45, -1);
            });

    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWrite(final IndexedRaftRecord indexed) {
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(log, timeout(1000).atLeast(3)).append(any(RaftLogEntry.class));
  }

  @Test
  public void shouldStopRetryAppendEntryAfterMaxRetries() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class))).thenThrow(new StorageException(new IOException()));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(log, timeout(1000).atLeast(5)).append(any(RaftLogEntry.class));
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    assertThat(caughtError.get()).isInstanceOf(IOException.class);
  }

  @Test
  public void shouldStopAppendEntryOnOutOfDisk() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class)))
        .thenThrow(new StorageException.OutOfDiskSpace("Boom file out"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    verify(log, timeout(1000)).append(any(RaftLogEntry.class));

    assertThat(caughtError.get()).isInstanceOf(StorageException.OutOfDiskSpace.class);
  }

  @Test
  public void shouldStopAppendEntryOnToLargeEntry() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class)))
        .thenThrow(new StorageException.TooLarge("Too large entry"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(log, timeout(1000)).append(any(RaftLogEntry.class));

    assertThat(caughtError.get()).isInstanceOf(StorageException.TooLarge.class);
  }

  @Test
  public void shouldTransitionToFollowerWhenAppendEntryException() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class))).thenThrow(new RuntimeException("expected"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(2, 3, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(log, timeout(1000)).append(any(RaftLogEntry.class));
    verify(context, timeout(1000)).transition(Role.FOLLOWER);

    assertThat(caughtError.get()).isInstanceOf(RuntimeException.class);
  }

  @Test
  public void shouldNotAppendFollowingEntryOnException() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class))).thenThrow(new RuntimeException("expected"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);

    // when
    leaderRole.appendEntry(0, 1, data, new AppendListener() {});
    leaderRole.appendEntry(
        2,
        3,
        data,
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }
        });

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    verify(log, timeout(1000)).append(any(RaftLogEntry.class));

    assertThat(caughtError.get())
        .isInstanceOf(NoLeader.class)
        .hasMessage("LeaderRole is closed and cannot be used as appender");
  }

  @Test
  public void shouldRetryAppendEntriesInOrder() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class)))
        .thenThrow(new StorageException(new IOException()))
        .thenThrow(new StorageException(new IOException()))
        .then(
            i -> {
              final ApplicationEntry applicationEntry = i.getArgument(0);
              return new IndexedRaftRecord(1, new RaftLogEntry(1, applicationEntry), 45, -1);
            });

    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final List<ApplicationEntry> entries = new CopyOnWriteArrayList<>();
    final CountDownLatch latch = new CountDownLatch(2);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWrite(final IndexedRaftRecord indexed) {
            entries.add(indexed.entry().getApplicationEntry());
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(0, 1, data, listener);
    leaderRole.appendEntry(1, 2, data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(log, timeout(1000).atLeast(3)).append(any(RaftLogEntry.class));

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).highestPosition()).isEqualTo(1);
    assertThat(entries.get(1).highestPosition()).isEqualTo(2);
  }

  @Test
  public void shouldDetectInconsistencyWithLastEntry() throws InterruptedException {
    // given
    when(log.append(any(RaftLogEntry.class)))
        .then(
            i -> {
              final ApplicationEntry applicationEntry = i.getArgument(0);
              return new IndexedRaftRecord(1, new RaftLogEntry(1, applicationEntry), 45, -1);
            });

    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(2);
    when(context.getEntryValidator())
        .thenReturn(
            (lastEntry, entry) -> {
              if (lastEntry != null) {
                assertThat(lastEntry.highestPosition()).isEqualTo(7);
                assertThat(entry.lowestPosition()).isEqualTo(9);
                assertThat(entry.highestPosition()).isEqualTo(9);
                entry.data().rewind();
                data.rewind();
                assertThat(entry.data()).isEqualTo(data);
                latch.countDown();
                return ValidationResult.failure("expected");
              }
              return ValidationResult.success();
            });
    leaderRole = new LeaderRole(context);

    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWriteError(final Throwable error) {
            latch.countDown();
          }
        };

    // when
    leaderRole.appendEntry(6, 7, data, new TestAppender());
    leaderRole.appendEntry(9, 9, data, listener);

    // then
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(leaderRole.raft, timeout(2000).atLeast(1)).transition(Role.FOLLOWER);
  }
}
