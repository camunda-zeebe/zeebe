/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.timer;

import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.EventHandle;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEvent;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.state.mutable.MutableTimerInstanceState;
import io.zeebe.model.bpmn.util.time.Interval;
import io.zeebe.model.bpmn.util.time.RepeatingInterval;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.util.Either;
import io.zeebe.util.buffer.BufferUtil;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class TriggerTimerProcessor implements TypedRecordProcessor<TimerRecord> {
  private static final String NO_TIMER_FOUND_MESSAGE =
      "Expected to trigger timer with key '%d', but no such timer was found";
  private static final String NO_ACTIVE_TIMER_MESSAGE =
      "Expected to trigger a timer with key '%d', but the timer is not active anymore";

  private static final DirectBuffer NO_VARIABLES = new UnsafeBuffer();

  private final CatchEventBehavior catchEventBehavior;
  private final ProcessState processState;
  private final ElementInstanceState elementInstanceState;
  private final MutableTimerInstanceState timerInstanceState;
  private final EventHandle eventHandle;
  private final ExpressionProcessor expressionProcessor;

  public TriggerTimerProcessor(
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventBehavior,
      final ExpressionProcessor expressionProcessor) {
    this.catchEventBehavior = catchEventBehavior;
    this.expressionProcessor = expressionProcessor;
    processState = zeebeState.getProcessState();
    elementInstanceState = zeebeState.getElementInstanceState();
    timerInstanceState = zeebeState.getTimerState();

    eventHandle =
        new EventHandle(zeebeState.getKeyGenerator(), zeebeState.getEventScopeInstanceState());
  }

  @Override
  public void processRecord(
      final TypedRecord<TimerRecord> record,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffects) {
    final TimerRecord timer = record.getValue();
    final long elementInstanceKey = timer.getElementInstanceKey();

    final TimerInstance timerInstance = timerInstanceState.get(elementInstanceKey, record.getKey());
    if (timerInstance == null) {
      streamWriter.appendRejection(
          record, RejectionType.NOT_FOUND, String.format(NO_TIMER_FOUND_MESSAGE, record.getKey()));
      return;
    }
    timerInstanceState.remove(timerInstance);

    processTimerTrigger(record, streamWriter, timer, elementInstanceKey, sideEffects);
  }

  private void processTimerTrigger(
      final TypedRecord<TimerRecord> record,
      final TypedStreamWriter streamWriter,
      final TimerRecord timer,
      final long elementInstanceKey,
      final Consumer<SideEffectProducer> sideEffects) {

    final var processDefinitionKey = timer.getProcessDefinitionKey();
    final var catchEvent =
        processState.getFlowElement(
            processDefinitionKey, timer.getTargetElementIdBuffer(), ExecutableCatchEvent.class);

    final boolean isTriggered =
        triggerEvent(streamWriter, timer, elementInstanceKey, processDefinitionKey, catchEvent);

    if (isTriggered) {
      streamWriter.appendFollowUpEvent(record.getKey(), TimerIntent.TRIGGERED, timer);

      if (shouldReschedule(timer)) {
        rescheduleTimer(timer, catchEvent, sideEffects);
      }
    } else {
      streamWriter.appendRejection(
          record,
          RejectionType.INVALID_STATE,
          String.format(NO_ACTIVE_TIMER_MESSAGE, record.getKey()));
    }
  }

  private boolean triggerEvent(
      final TypedStreamWriter streamWriter,
      final TimerRecord timer,
      final long elementInstanceKey,
      final long processDefinitionKey,
      final ExecutableCatchEvent catchEvent) {

    if (elementInstanceKey > 0) {
      final var elementInstance = elementInstanceState.getInstance(elementInstanceKey);

      if (elementInstance != null && elementInstance.isActive()) {
        return eventHandle.triggerEvent(streamWriter, elementInstance, catchEvent, NO_VARIABLES);

      } else {
        return false;
      }

    } else {
      final var processInstanceKey =
          eventHandle.triggerStartEvent(
              streamWriter, processDefinitionKey, timer.getTargetElementIdBuffer(), NO_VARIABLES);

      return processInstanceKey > 0;
    }
  }

  private boolean shouldReschedule(final TimerRecord timer) {
    return timer.getRepetitions() == RepeatingInterval.INFINITE || timer.getRepetitions() > 1;
  }

  private void rescheduleTimer(
      final TimerRecord record,
      final ExecutableCatchEvent event,
      final Consumer<SideEffectProducer> sideEffects) {
    final Either<Failure, Timer> timer =
        event.getTimerFactory().apply(expressionProcessor, record.getElementInstanceKey());
    if (timer.isLeft()) {
      final String message =
          String.format(
              "Expected to reschedule repeating timer for element with id '%s', but an error occurred: %s",
              BufferUtil.bufferAsString(event.getId()), timer.getLeft().getMessage());
      throw new IllegalStateException(message);
      // todo(#4208): raise incident instead of throwing an exception
    }

    int repetitions = record.getRepetitions();
    if (repetitions != RepeatingInterval.INFINITE) {
      repetitions--;
    }

    final Interval interval = timer.map(Timer::getInterval).get();
    final Timer repeatingInterval = new RepeatingInterval(repetitions, interval);
    catchEventBehavior.subscribeToTimerEvent(
        record.getElementInstanceKey(),
        record.getProcessInstanceKey(),
        record.getProcessDefinitionKey(),
        event.getId(),
        repeatingInterval,
        sideEffects);
  }
}
