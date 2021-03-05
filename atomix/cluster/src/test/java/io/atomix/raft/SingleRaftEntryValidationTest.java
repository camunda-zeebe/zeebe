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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.zeebe.EntryValidator;
import io.atomix.raft.zeebe.ValidationResult;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.Rule;
import org.junit.Test;

public class SingleRaftEntryValidationTest {

  private final TestEntryValidator entryValidator = new TestEntryValidator();

  @Rule
  public RaftRule raftRule = RaftRule.withBootstrappedNodes(1).setEntryValidator(entryValidator);

  @Test
  public void shouldFailAppendOnInvalidEntry() {
    // given
    entryValidator.validation = (last, current) -> ValidationResult.failure("invalid");

    // when - then expect
    assertThatThrownBy(() -> raftRule.appendEntry()).hasMessageContaining("invalid");
  }

  @Test
  public void shouldNotAppendInvalidEntryToLog() throws Exception {
    // given
    entryValidator.validation = (last, current) -> ValidationResult.failure("invalid");

    // when
    assertThatThrownBy(() -> raftRule.appendEntry()).hasMessageContaining("invalid");
    entryValidator.validation = (last, current) -> ValidationResult.success();
    raftRule.awaitNewLeader();
    final var commitIndex =
        raftRule.appendEntry(); // append another entry to await the commit index

    // then
    raftRule.awaitCommit(commitIndex);
    raftRule.awaitSameLogSizeOnAllNodes(commitIndex);
    final var memberLog = raftRule.getMemberLogs();

    final var logLength = memberLog.values().stream().map(List::size).findFirst().orElseThrow();
    assertThat(logLength).withFailMessage(memberLog.toString()).isEqualTo(3);
  }

  private static class TestEntryValidator implements EntryValidator {
    BiFunction<ApplicationEntry, ApplicationEntry, ValidationResult> validation;

    @Override
    public ValidationResult validateEntry(
        final ApplicationEntry lastEntry, final ApplicationEntry entry) {
      return validation.apply(lastEntry, entry);
    }
  }
}
