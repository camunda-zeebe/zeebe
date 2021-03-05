/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.logstreams.storage.atomix;

import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.zeebe.logstreams.storage.LogStorageReader;
import java.util.NoSuchElementException;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Implements {@link LogStorageReader} over a {@link RaftLogReader}. Each {@link ApplicationEntry}
 * is considered a block (as per the log storage definition).
 *
 * <p>The implementation does look-ahead by one entry. This is necessary because we usually want to
 * seek to the entry which contains the given position, and in order to know that it does we need to
 * read it, and only then return it via the next {@link #next()} call. It is also safe as we read
 * only committed entries, which may be compacted but remain valid.
 *
 * <p>Note that due to the look-ahead, calling {@link #hasNext()} may result in doing some I/O and
 * mutating the state of the reader.
 *
 * <p>The reader currently simply returns the block as is without copying it - this is safe at the
 * moment because the serialization in the underlying {@link io.atomix.raft.storage.log.RaftLog}
 * already copies the data from disk. When switching to zero-copy, however, because of the
 * look-ahead, this reader will have to copy the block. At that point, we may want to look into
 * doing more than a single-step look-ahead (either here or in the {@link
 * io.zeebe.logstreams.log.LogStreamReader}).
 */
public final class AtomixLogStorageReader implements LogStorageReader {

  private final RaftLogReader reader;
  private final DirectBuffer currentBlockBuffer;
  private final DirectBuffer nextBlockBuffer;

  public AtomixLogStorageReader(final RaftLogReader reader) {
    this.reader = reader;

    currentBlockBuffer = new UnsafeBuffer();
    nextBlockBuffer = new UnsafeBuffer();

    reset();
  }

  @Override
  public void seek(final long position) {
    // bounding the position to 0 means we will always seek to the first valid ASQN on the log if
    // any
    final long boundedPosition = Math.max(0, position);

    reader.seekToAsqn(boundedPosition);
    reset();
    readNextBlock();
  }

  @Override
  public void close() {
    reset();
    reader.close();
  }

  @Override
  public boolean hasNext() {
    return hasNextBlock() || readNextBlock();
  }

  @Override
  public DirectBuffer next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    currentBlockBuffer.wrap(nextBlockBuffer);
    nextBlockBuffer.wrap(0, 0);

    return currentBlockBuffer;
  }

  private boolean hasNextBlock() {
    return nextBlockBuffer.addressOffset() != 0;
  }

  private boolean readNextBlock() {
    while (reader.hasNext()) {
      final IndexedRaftRecord entry = reader.next();
      if (entry.entry().isApplicationEntry()) {
        final ApplicationEntry nextEntry = entry.entry().getApplicationEntry();

        nextBlockBuffer.wrap(nextEntry.data());
        return true;
      }
    }

    return false;
  }

  private void reset() {
    currentBlockBuffer.wrap(0, 0);
    nextBlockBuffer.wrap(0, 0);
  }
}
