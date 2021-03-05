/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.log;

import static com.google.common.base.MoreObjects.toStringHelper;

import io.atomix.raft.storage.log.entry.RaftLogEntry;
import java.util.Objects;

/** Indexed journal entry. */
public class IndexedRaftRecord {

  private final long index;
  private final RaftLogEntry entry;
  private final int size;
  private final long checksum;

  public IndexedRaftRecord(
      final long index, final RaftLogEntry entry, final int size, final long checksum) {
    this.index = index;
    this.entry = entry;
    this.size = size;
    this.checksum = checksum;
  }

  /**
   * Returns the entry index.
   *
   * @return The entry index.
   */
  public long index() {
    return index;
  }

  /**
   * Returns the indexed entry.
   *
   * @return The indexed entry.
   */
  public RaftLogEntry entry() {
    return entry;
  }

  /**
   * Returns the serialized entry size.
   *
   * @return The serialized entry size.
   */
  public int size() {
    return size;
  }

  /**
   * Returns the entry checksum.
   *
   * @return The entry checksum.
   */
  public long checksum() {
    return checksum;
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, entry, size);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final IndexedRaftRecord indexed = (IndexedRaftRecord) o;
    return index == indexed.index
        && size == indexed.size
        && Objects.equals(entry, indexed.entry)
        && checksum == indexed.checksum;
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("index", index)
        .add("entry", entry)
        .add("checksum", checksum)
        .toString();
  }
}
