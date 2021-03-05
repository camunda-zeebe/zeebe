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
package io.atomix.raft;

import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RaftAppendTest {

  @Rule @Parameter public RaftRule raftRule;

  @Parameters(name = "{index}: {0}")
  public static Object[][] raftConfigurations() {
    return new Object[][] {
      new Object[] {RaftRule.withBootstrappedNodes(2)},
      new Object[] {RaftRule.withBootstrappedNodes(3)},
      new Object[] {RaftRule.withBootstrappedNodes(4)},
      new Object[] {RaftRule.withBootstrappedNodes(5)}
    };
  }

  @Test
  public void shouldAppendEntryOnAllNodes() throws Throwable {
    // given

    // when
    final long commitIndex = raftRule.appendEntry();

    // then
    raftRule.awaitCommit(commitIndex);
    raftRule.awaitSameLogSizeOnAllNodes(commitIndex);
    final var memberLog = raftRule.getMemberLogs();

    final var logLength = memberLog.values().stream().map(List::size).findFirst().orElseThrow();
    assertThat(logLength).withFailMessage(memberLog.toString()).isEqualTo(2);

    assertMemberLogs(memberLog);
  }

  @Test
  public void shouldAppendEntriesOnAllNodes() throws Throwable {
    // given
    final var entryCount = 128;

    // when
    final var lastIndex = raftRule.appendEntries(entryCount);

    // then
    raftRule.awaitSameLogSizeOnAllNodes(lastIndex);
    final var memberLog = raftRule.getMemberLogs();

    final var maxIndex =
        memberLog.values().stream()
            .flatMap(Collection::stream)
            .map(IndexedRaftRecord::index)
            .max(Long::compareTo)
            .orElseThrow();
    assertThat(maxIndex).isEqualTo(lastIndex);
    assertThat(lastIndex).isEqualTo(entryCount + 1);
    assertMemberLogs(memberLog);
  }

  private void assertMemberLogs(final Map<String, List<IndexedRaftRecord>> memberLog) {
    final var firstMemberEntries = memberLog.get("1");
    final var members = memberLog.keySet();
    for (final var member : members) {
      if (!member.equals("1")) {
        final var otherEntries = memberLog.get(member);

        assertThat(otherEntries)
            .describedAs("Entry comparison 1 vs " + member)
            .containsExactly(firstMemberEntries.toArray(new IndexedRaftRecord[0]));
      }
    }
  }
}
