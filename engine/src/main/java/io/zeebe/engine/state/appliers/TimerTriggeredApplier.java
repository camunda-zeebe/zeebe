/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEvent;
import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableTimerInstanceState;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.intent.TimerIntent;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

final class TimerTriggeredApplier implements TypedEventApplier<TimerIntent, TimerRecord> {

  private static final UnsafeBuffer NO_VARIABLES = new UnsafeBuffer();
  private final MutableEventScopeInstanceState eventScopeInstanceState;
  private final MutableTimerInstanceState timerInstanceState;
  private final ProcessState processState;

  public TimerTriggeredApplier(
      final MutableEventScopeInstanceState eventScopeInstanceState,
      final MutableTimerInstanceState timerInstanceState,
      final ProcessState processState) {
    this.eventScopeInstanceState = eventScopeInstanceState;
    this.timerInstanceState = timerInstanceState;
    this.processState = processState;
  }

  @Override
  public void applyState(final long key, final TimerRecord value) {
    final long elementInstanceKey = value.getElementInstanceKey();
    final TimerInstance timerInstance = timerInstanceState.get(elementInstanceKey, key);
    final long processDefinitionKey = value.getProcessDefinitionKey();
    final DirectBuffer targetElementId = value.getTargetElementIdBuffer();

    timerInstanceState.remove(timerInstance);

    if (elementInstanceKey > 0) {
      final ExecutableCatchEvent catchEvent =
          processState.getFlowElement(
              processDefinitionKey, targetElementId, ExecutableCatchEvent.class);
      eventScopeInstanceState.triggerEvent(
          elementInstanceKey, key, catchEvent.getId(), NO_VARIABLES);
    } else {
      eventScopeInstanceState.triggerEvent(
          processDefinitionKey, key, targetElementId, NO_VARIABLES);
    }
  }
}
