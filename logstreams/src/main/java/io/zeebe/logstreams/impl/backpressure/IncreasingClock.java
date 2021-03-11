/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl.backpressure;

import java.util.function.Supplier;

public class IncreasingClock implements Supplier<Long> {

  private Long lastTime = null;

  @Override
  public synchronized Long get() {
    long time = System.nanoTime();
    if (lastTime != null && time <= lastTime) {
      time = lastTime + 1;
    }

    lastTime = time;
    return time;
  }
}
