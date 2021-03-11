/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl.backpressure;

import com.netflix.concurrency.limits.limiter.AbstractLimiter;
import io.zeebe.logstreams.impl.Loggers;
import java.util.Optional;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppendEntryLimiter extends AbstractLimiter<Long> implements AppendLimiter {

  private static final Logger LOG =
      LoggerFactory.getLogger("io.zeebe.logstreams.impl.backpressure");
  private final Long2ObjectHashMap<Listener> appendedListeners = new Long2ObjectHashMap<>();
  private final AppendBackpressureMetrics metrics;

  private AppendEntryLimiter(final AppendEntryLimiterBuilder builder, final int partitionId) {
    super(builder);
    metrics = new AppendBackpressureMetrics(partitionId);
    metrics.setInflight(0);
    metrics.setNewLimit(getLimit());
  }

  public Optional<Listener> acquire(final Long position) {
    if (getInflight() >= getLimit()) {
      return createRejectedListener();
    }
    final Listener listener = createListener();
    return Optional.of(listener);
  }

  private void registerListener(final long position, final Listener listener) {
    appendedListeners.put(position, listener);
  }

  public boolean tryAcquire(final Long position) {
    final Optional<Listener> acquired = acquire(position);
    return acquired
        .map(
            listener -> {
              registerListener(position, listener);
              metrics.incInflight();
              return true;
            })
        .orElse(false);
  }

  public void onCommit(final long position) {
    final Listener listener = appendedListeners.remove(position);
    if (listener != null) {
      try {
        listener.onSuccess();
      } catch (final IllegalArgumentException e) {
        listener.onIgnore();
        LOG.trace("Ignoring request.");
      }
      metrics.decInflight();
    } else {
      Loggers.LOGSTREAMS_LOGGER.warn(
          "We encountered an problem on releasing the acquired in flight append."
              + " There was no listener registered for the given position {}, this should not happen.",
          position);
    }
  }

  @Override
  protected void onNewLimit(final int newLimit) {
    super.onNewLimit(newLimit);
    metrics.setNewLimit(newLimit);
  }

  public static AppendEntryLimiterBuilder builder() {
    return new AppendEntryLimiterBuilder();
  }

  public static class AppendEntryLimiterBuilder
      extends AbstractLimiter.Builder<AppendEntryLimiterBuilder> {

    private int partitionId;

    @Override
    protected AppendEntryLimiterBuilder self() {
      return this;
    }

    public AppendEntryLimiterBuilder partitionId(final int partition) {
      partitionId = partition;
      return this;
    }

    public AppendEntryLimiter build() {
      return new AppendEntryLimiter(this, partitionId);
    }
  }
}
