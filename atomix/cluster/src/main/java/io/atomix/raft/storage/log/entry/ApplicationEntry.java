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
package io.atomix.raft.storage.log.entry;

import static com.google.common.base.MoreObjects.toStringHelper;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Stores an entry that contains serialized records, ordered by their position; the lowestPosition
 * and highestPosition metadata allow for fast binary search over a collection of entries to quickly
 * find a particular record.
 */
public class ApplicationEntry implements RaftEntry {

  private final long lowestPosition;
  private final long highestPosition;
  private final DirectBuffer data = new UnsafeBuffer();

  public ApplicationEntry(
      final long lowestPosition, final long highestPosition, final ByteBuffer data) {
    this.lowestPosition = lowestPosition;
    this.highestPosition = highestPosition;
    this.data.wrap(data);
  }

  public ApplicationEntry(
      final long lowestPosition, final long highestPosition, final DirectBuffer data) {
    this.lowestPosition = lowestPosition;
    this.highestPosition = highestPosition;
    this.data.wrap(data);
  }

  public long lowestPosition() {
    return lowestPosition;
  }

  public long highestPosition() {
    return highestPosition;
  }

  public DirectBuffer data() {
    return data;
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("lowestPosition", lowestPosition())
        .add("highestPosition", highestPosition())
        .toString();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ApplicationEntry that = (ApplicationEntry) o;
    return lowestPosition == that.lowestPosition
        && highestPosition == that.highestPosition
        && data.equals(that.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(lowestPosition, highestPosition, data);
  }
}
