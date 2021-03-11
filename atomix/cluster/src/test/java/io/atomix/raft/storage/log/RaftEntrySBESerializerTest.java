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
package io.atomix.raft.storage.log;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.cluster.MemberId;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.RaftMember.Type;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.raft.storage.log.entry.InitializeEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import java.time.Instant;
import java.util.Set;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

public class RaftEntrySBESerializerTest {

  final RaftEntrySerializer serializer = new RaftEntrySBESerializer();
  final MutableDirectBuffer buffer = new ExpandableArrayBuffer();

  @Test
  public void shouldWriteApplicationEntry() {
    // given
    final byte[] data = "Test".getBytes();
    final ApplicationEntry entry = new ApplicationEntry(1, 2, new UnsafeBuffer(data));
    final RaftLogEntry raftLogEntry = new RaftLogEntry(5, entry);

    // when
    final var length = serializer.writeApplicationEntry(5, entry, buffer, 0);
    final RaftLogEntry entryRead = serializer.getRaftLogEntry(buffer);

    assertThat(entryRead.isApplicationEntry()).isTrue();

    final ApplicationEntry applicationEntryRead = entryRead.getApplicationEntry();

    // then
    assertThat(applicationEntryRead).isEqualTo(entry);
  }

  @Test
  public void shouldWriteInitialEntryEntry() {
    // given
    final byte[] data = "Test".getBytes();
    final InitializeEntry entry = new InitializeEntry();
    final RaftLogEntry raftLogEntry = new RaftLogEntry(5, entry);

    // when
    final var length = serializer.writeInitialEntry(5, entry, buffer, 0);
    final RaftLogEntry entryRead = serializer.getRaftLogEntry(buffer);

    assertThat(entryRead.isInitialEntry()).isTrue();
    assertThat(raftLogEntry).isEqualTo(entryRead);
  }

  @Test
  public void shouldWriteConfigurationEntry() {
    // given
    final byte[] data = "Test".getBytes();
    final Set<RaftMember> members =
        Set.of(
            new DefaultRaftMember(MemberId.from("1"), Type.ACTIVE, Instant.ofEpochMilli(123456L)),
            new DefaultRaftMember(MemberId.from("2"), Type.PASSIVE, Instant.ofEpochMilli(123457L)),
            new DefaultRaftMember(
                MemberId.from("3"), Type.PROMOTABLE, Instant.ofEpochMilli(123458L)));
    final ConfigurationEntry entry = new ConfigurationEntry(1234L, members);
    final RaftLogEntry raftLogEntry = new RaftLogEntry(5, entry);

    // when
    final var length = serializer.writeConfigurationEntry(5, entry, buffer, 0);
    final RaftLogEntry entryRead = serializer.getRaftLogEntry(buffer);

    assertThat(entryRead.isConfigurationEntry()).isTrue();
    final var configEntry = entryRead.getConfigurationEntry();
    assertThat(configEntry.timestamp()).isEqualTo(entry.timestamp());
    assertThat(configEntry.toString()).isEqualTo(entry.toString());
  }
}
