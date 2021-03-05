/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.system.partitions.impl;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.storage.log.RaftLogReader;
import io.zeebe.broker.system.partitions.AtomixRecordEntrySupplier;
import java.util.Optional;

public final class AtomixRecordEntrySupplierImpl implements AtomixRecordEntrySupplier {
  private final RaftLogReader reader;

  public AtomixRecordEntrySupplierImpl(final RaftLogReader reader) {
    this.reader = reader;
  }

  @Override
  public Optional<IndexedRaftRecord> getPreviousIndexedEntry(final long position) {
    // Here we are seeking twice. Since this method is only called when taking a snapshot it is ok
    // to be not very efficient.
    final long recordIndex = reader.seekToAsqn(position);
    final long prevIndex = recordIndex - 1;
    if (reader.reset(prevIndex) == prevIndex) {
      return Optional.of(reader.next());
    }

    return Optional.empty();
  }

  @Override
  public void close() {
    reader.close();
  }
}
