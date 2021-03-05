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
package io.atomix.raft.zeebe.util;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.raft.zeebe.ZeebeLogAppender.AppendListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TestAppender implements AppendListener {
  private final BlockingQueue<IndexedRaftRecord> written;
  private final BlockingQueue<IndexedRaftRecord> committed;
  private final BlockingQueue<Throwable> errors;

  public TestAppender() {
    written = new LinkedBlockingQueue<>();
    committed = new LinkedBlockingQueue<>();
    errors = new LinkedBlockingQueue<>();
  }

  @Override
  public void onWrite(final IndexedRaftRecord indexed) {
    written.offer(indexed);
  }

  @Override
  public void onWriteError(final Throwable error) {
    errors.offer(error);
  }

  @Override
  public void onCommit(final IndexedRaftRecord indexed) {
    committed.offer(indexed);
  }

  @Override
  public void onCommitError(final IndexedRaftRecord indexed, final Throwable error) {
    errors.offer(error);
  }

  public IndexedRaftRecord append(
      final ZeebeLogAppender appender,
      final long lowest,
      final long highest,
      final ByteBuffer data) {
    appender.appendEntry(lowest, highest, data, this);
    return pollWritten();
  }

  public IndexedRaftRecord pollWritten() {
    return takeUnchecked(written);
  }

  public IndexedRaftRecord pollCommitted() {
    return takeUnchecked(committed);
  }

  public Throwable pollError() {
    return takeUnchecked(errors);
  }

  public List<IndexedRaftRecord> getWritten() {
    return new ArrayList<>(written);
  }

  public List<IndexedRaftRecord> getCommitted() {
    return new ArrayList<>(committed);
  }

  public List<Throwable> getErrors() {
    return new ArrayList<>(errors);
  }

  private <T> T takeUnchecked(final BlockingQueue<T> queue) {
    try {
      return queue.take();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
