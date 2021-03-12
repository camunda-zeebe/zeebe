/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.state.mutable.MutableTimerInstanceState;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.intent.TimerIntent;

class TimerCancelledApplier implements TypedEventApplier<TimerIntent, TimerRecord> {

  private final MutableTimerInstanceState timerInstanceState;

  TimerCancelledApplier(final MutableTimerInstanceState timerInstanceState) {
    this.timerInstanceState = timerInstanceState;
  }

  @Override
  public void applyState(final long key, final TimerRecord value) {
    final TimerInstance timerInstance = timerInstanceState.get(value.getElementInstanceKey(), key);

    // if (timerInstance != null) {
    timerInstanceState.remove(timerInstance);
    // } else {
    //  System.out.println("klongh");
    // }
  }
}
