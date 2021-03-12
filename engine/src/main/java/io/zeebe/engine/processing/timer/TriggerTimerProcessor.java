/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.timer;

import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEvent;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedRejectionWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.immutable.EventScopeInstanceState;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.mutable.MutableTimerInstanceState;
import io.zeebe.model.bpmn.util.time.Interval;
import io.zeebe.model.bpmn.util.time.RepeatingInterval;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.util.Either;
import io.zeebe.util.buffer.BufferUtil;
import java.util.function.Consumer;

public final class TriggerTimerProcessor implements TypedRecordProcessor<TimerRecord> {

  private static final String NO_TIMER_FOUND_MESSAGE =
      "Expected to trigger timer with key '%d', but no such timer was found";
  private static final String NO_ACTIVE_TIMER_MESSAGE =
      "Expected to trigger a timer with key '%d', but the timer is not active anymore";

  private final CatchEventBehavior catchEventBehavior;
  private final ProcessState processState;
  private final ElementInstanceState elementInstanceState;
  private final MutableTimerInstanceState timerInstanceState;
  private final ExpressionProcessor expressionProcessor;
  private final KeyGenerator keyGenerator;
  private final EventScopeInstanceState eventScopeInstanceState;
  private final StateWriter stateWriter;
  private final TypedRejectionWriter rejectionWriter;

  private final ProcessInstanceRecord eventOccurredRecord = new ProcessInstanceRecord();

  public TriggerTimerProcessor(
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventBehavior,
      final ExpressionProcessor expressionProcessor,
      final StateWriter stateWriter,
      final TypedRejectionWriter rejectionWriter) {
    this.catchEventBehavior = catchEventBehavior;
    this.expressionProcessor = expressionProcessor;
    this.stateWriter = stateWriter;
    this.rejectionWriter = rejectionWriter;

    processState = zeebeState.getProcessState();
    elementInstanceState = zeebeState.getElementInstanceState();
    timerInstanceState = zeebeState.getTimerState();
    keyGenerator = zeebeState.getKeyGenerator();
    eventScopeInstanceState = zeebeState.getEventScopeInstanceState();
  }

  @Override
  public void processRecord(
      final TypedRecord<TimerRecord> record,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffects) {
    final var timer = record.getValue();
    final var elementInstanceKey = timer.getElementInstanceKey();
    final var processDefinitionKey = timer.getProcessDefinitionKey();

    final var timerInstance = timerInstanceState.get(elementInstanceKey, record.getKey());
    if (timerInstance == null) {
      rejectionWriter.appendRejection(
          record, RejectionType.NOT_FOUND, String.format(NO_TIMER_FOUND_MESSAGE, record.getKey()));
      return;
    }

    if (!hasActiveTimer(elementInstanceKey, processDefinitionKey)) {
      rejectionWriter.appendRejection(
          record,
          RejectionType.INVALID_STATE,
          String.format(NO_ACTIVE_TIMER_MESSAGE, record.getKey()));
      return;
    }

    final var catchEvent =
        processState.getFlowElement(
            processDefinitionKey, timer.getTargetElementIdBuffer(), ExecutableCatchEvent.class);

    final long eventOccurredKey;
    eventOccurredRecord.reset();
    if (isStartEvent(elementInstanceKey)) {
      final var processInstanceKey = keyGenerator.nextKey();
      eventOccurredKey = keyGenerator.nextKey();
      eventOccurredRecord
          .setBpmnElementType(BpmnElementType.START_EVENT)
          .setProcessDefinitionKey(processDefinitionKey)
          .setProcessInstanceKey(processInstanceKey)
          .setElementId(timer.getTargetElementIdBuffer());
    } else {
      final var elementInstance = elementInstanceState.getInstance(elementInstanceKey);

      eventOccurredRecord.wrap(elementInstance.getValue());
      if (isEventSubprocess(catchEvent)) {
        eventOccurredKey = keyGenerator.nextKey();
        eventOccurredRecord
            .setElementId(catchEvent.getId())
            .setBpmnElementType(BpmnElementType.START_EVENT)
            .setFlowScopeKey(elementInstance.getKey());
      } else {
        eventOccurredKey = elementInstanceKey;
      }
    }

    stateWriter.appendFollowUpEvent(record.getKey(), TimerIntent.TRIGGERED, timer);
    stateWriter.appendFollowUpEvent(
        eventOccurredKey, ProcessInstanceIntent.EVENT_OCCURRED, eventOccurredRecord);

    if (shouldReschedule(timer)) {
      rescheduleTimer(timer, catchEvent, sideEffects);
    }
  }

  private boolean hasActiveTimer(final long elementInstanceKey, final long processDefinitionKey) {
    final boolean result;
    if (isStartEvent(elementInstanceKey)) {
      result = eventScopeInstanceState.isAcceptingEvent(processDefinitionKey);
    } else {
      final var elementInstance = elementInstanceState.getInstance(elementInstanceKey);
      result =
          (elementInstance != null
              && elementInstance.isActive()
              && eventScopeInstanceState.isAcceptingEvent(elementInstanceKey));
    }
    return result;
  }

  private boolean isStartEvent(final long elementInstancekey) {
    return elementInstancekey < 0;
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

  private boolean isEventSubprocess(final ExecutableFlowElement catchEvent) {
    return catchEvent instanceof ExecutableStartEvent
        && ((ExecutableStartEvent) catchEvent).getEventSubProcess() != null;
  }
}
