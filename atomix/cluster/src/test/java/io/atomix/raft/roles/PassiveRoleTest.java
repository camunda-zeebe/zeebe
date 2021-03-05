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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.metrics.RaftReplicationMetrics;
import io.atomix.raft.protocol.AppendRequest;
import io.atomix.raft.protocol.AppendResponse;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.utils.serializer.Namespace;
import io.atomix.utils.serializer.Namespace.Builder;
import io.atomix.utils.serializer.Namespaces;
import io.zeebe.snapshots.raft.PersistedSnapshot;
import io.zeebe.snapshots.raft.ReceivableSnapshotStore;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class PassiveRoleTest {

  private static final Namespace NAMESPACE =
      new Builder().register(Namespaces.BASIC).register(ApplicationEntry.class).build();
  @Rule public Timeout timeout = new Timeout(30, TimeUnit.SECONDS);
  private final ApplicationEntry entry = new ApplicationEntry(0, 1, ByteBuffer.allocate(0));
  private RaftLog log;
  private PassiveRole role;

  @Before
  public void setup() {
    final RaftStorage storage = mock(RaftStorage.class);
    when(storage.namespace()).thenReturn(NAMESPACE);

    log = mock(RaftLog.class);
    when(log.getLastIndex()).thenReturn(1L);

    final PersistedSnapshot snapshot = mock(PersistedSnapshot.class);
    when(snapshot.getIndex()).thenReturn(1L);
    when(snapshot.getTerm()).thenReturn(1L);

    final ReceivableSnapshotStore store = mock(ReceivableSnapshotStore.class);
    when(store.getLatestSnapshot()).thenReturn(Optional.of(snapshot));

    final RaftContext ctx = mock(RaftContext.class);
    when(ctx.getStorage()).thenReturn(storage);
    when(ctx.getLog()).thenReturn(log);
    when(ctx.getPersistedSnapshotStore()).thenReturn(store);
    when(ctx.getTerm()).thenReturn(1L);
    when(ctx.getReplicationMetrics()).thenReturn(mock(RaftReplicationMetrics.class));
    when(ctx.getLog()).thenReturn(mock(RaftLog.class));

    role = new PassiveRole(ctx);
  }

  @Test
  public void shouldRejectRequestIfDifferentNumberEntriesAndChecksums() {
    // given
    final List<RaftLogEntry> entries = generateEntries(1);
    final AppendRequest request = new AppendRequest(2, "", 1, 1, entries, List.of(1L, 2L), 1);

    // when
    final AppendResponse response = role.handleAppend(request).join();

    // then
    assertThat(response.succeeded()).isFalse();
  }

  // TODO: should be replaced with a test that checks we correctly handle InvalidChecksum error
  @Ignore("should be replaced with a test that checks we correctly handle InvalidChecksum error")
  @Test
  public void shouldFailAppendWithIncorrectChecksum() {}

  private List<RaftLogEntry> generateEntries(final int numEntries) {
    final List<RaftLogEntry> entries = new ArrayList<>();
    for (int i = 0; i < numEntries; i++) {
      entries.add(new RaftLogEntry(1, new ApplicationEntry(i + 1, i + 1, ByteBuffer.allocate(0))));
    }

    return entries;
  }

  private List<Long> getChecksums(final List<RaftLogEntry> entries) {
    final List<Long> checksums = new ArrayList<>();

    for (final RaftLogEntry entry : entries) {
      final byte[] serialized = NAMESPACE.serialize(entry);
      final Checksum crc32 = new CRC32();
      crc32.update(serialized, 0, serialized.length);

      checksums.add(crc32.getValue());
    }

    return checksums;
  }
}
