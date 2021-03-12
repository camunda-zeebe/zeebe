/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.behavior;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnProcessingException;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableActivity;
import io.zeebe.engine.processing.deployment.model.element.ExecutableBoundaryEvent;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processing.deployment.model.element.ExecutableEventBasedGateway;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowNode;
import io.zeebe.engine.processing.deployment.model.element.ExecutableReceiveTask;
import io.zeebe.engine.processing.deployment.model.element.ExecutableSequenceFlow;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.message.MessageCorrelationKeyException;
import io.zeebe.engine.processing.message.MessageNameException;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffects;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.deployment.DeployedProcess;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.instance.EventTrigger;
import io.zeebe.engine.state.instance.StoredRecord.Purpose;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableVariableState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.util.Either;
import java.util.function.ToLongFunction;

public final class BpmnEventSubscriptionBehavior {

  private static final String NO_PROCESS_FOUND_MESSAGE =
      "Expected to create an instance of process with key '%d', but no such process was found";
  private static final String NO_TRIGGERED_EVENT_MESSAGE =
      "Expected to create an instance of process with key '%d', but no triggered event could be found";

  private final ProcessInstanceRecord eventRecord = new ProcessInstanceRecord();
  private final ProcessInstanceRecord recordForWFICreation = new ProcessInstanceRecord();

  private final BpmnStateBehavior stateBehavior;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final MutableEventScopeInstanceState eventScopeInstanceState;
  private final MutableElementInstanceState elementInstanceState;
  private final CatchEventBehavior catchEventBehavior;

  private final StateWriter stateWriter;
  private final SideEffects sideEffects;

  private final KeyGenerator keyGenerator;
  private final ProcessState processState;
  private final MutableVariableState variablesState;
  private final TypedCommandWriter commandWriter;

  public BpmnEventSubscriptionBehavior(
      final BpmnStateBehavior stateBehavior,
      final BpmnStateTransitionBehavior stateTransitionBehavior,
      final CatchEventBehavior catchEventBehavior,
      final StateWriter stateWriter,
      final TypedCommandWriter commandWriter,
      final SideEffects sideEffects,
      final ZeebeState zeebeState) {
    this.stateBehavior = stateBehavior;
    this.stateTransitionBehavior = stateTransitionBehavior;
    this.catchEventBehavior = catchEventBehavior;
    this.stateWriter = stateWriter;
    this.commandWriter = commandWriter;
    this.sideEffects = sideEffects;

    processState = zeebeState.getProcessState();
    eventScopeInstanceState = zeebeState.getEventScopeInstanceState();
    elementInstanceState = zeebeState.getElementInstanceState();
    keyGenerator = zeebeState.getKeyGenerator();
    variablesState = zeebeState.getVariableState();
  }

  public <T extends ExecutableCatchEventSupplier> Either<Failure, Void> subscribeToEvents(
      final T element, final BpmnElementContext context) {

    try {
      catchEventBehavior.subscribeToEvents(context, element, sideEffects);
      return Either.right(null);

    } catch (final MessageCorrelationKeyException e) {
      return Either.left(
          new Failure(e.getMessage(), ErrorType.EXTRACT_VALUE_ERROR, e.getVariableScopeKey()));

    } catch (final EvaluationException e) {
      return Either.left(
          new Failure(
              e.getMessage(), ErrorType.EXTRACT_VALUE_ERROR, context.getElementInstanceKey()));
    } catch (final MessageNameException e) {
      return Either.left(e.getFailure());
    }
  }

  public void unsubscribeFromEvents(final BpmnElementContext context) {
    catchEventBehavior.unsubscribeFromEvents(context, commandWriter, sideEffects);
  }

  public void triggerBoundaryOrIntermediateEvent(
      final ExecutableReceiveTask element, final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          final boolean hasEventTriggeredForBoundaryEvent =
              element.getBoundaryEvents().stream()
                  .anyMatch(
                      boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()));

          if (hasEventTriggeredForBoundaryEvent) {
            return triggerBoundaryEvent(element, context, eventTrigger);

          } else {
            stateTransitionBehavior.transitionToCompleting(context);
            return context.getElementInstanceKey();
          }
        });
  }

  public void triggerIntermediateEvent(final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          stateTransitionBehavior.transitionToCompleting(context);
          return context.getElementInstanceKey();
        });
  }

  public void triggerBoundaryEvent(
      final ExecutableActivity element, final BpmnElementContext context) {

    triggerEvent(context, eventTrigger -> triggerBoundaryEvent(element, context, eventTrigger));
  }

  private long triggerBoundaryEvent(
      final ExecutableActivity element,
      final BpmnElementContext context,
      final EventTrigger eventTrigger) {

    final var record =
        getEventRecord(context.getRecordValue(), eventTrigger, BpmnElementType.BOUNDARY_EVENT);

    final var boundaryEvent = getBoundaryEvent(element, context, eventTrigger);

    final long boundaryElementInstanceKey = keyGenerator.nextKey();
    if (boundaryEvent.interrupting()) {

      deferActivatingEvent(context, boundaryElementInstanceKey, record);

      stateTransitionBehavior.transitionToTerminating(context);

    } else {
      publishActivatingEvent(boundaryElementInstanceKey, record);
    }

    return boundaryElementInstanceKey;
  }

  private ProcessInstanceRecord getEventRecord(
      final ProcessInstanceRecord value,
      final EventTrigger event,
      final BpmnElementType elementType) {
    eventRecord.reset();
    eventRecord.wrap(value);
    eventRecord.setElementId(event.getElementId());
    eventRecord.setBpmnElementType(elementType);

    return eventRecord;
  }

  private <T extends ExecutableActivity> ExecutableBoundaryEvent getBoundaryEvent(
      final T element, final BpmnElementContext context, final EventTrigger eventTrigger) {

    return element.getBoundaryEvents().stream()
        .filter(boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    context,
                    String.format(
                        "Expected boundary event with id '%s' but not found.",
                        bufferAsString(eventTrigger.getElementId()))));
  }

  private void deferActivatingEvent(
      final BpmnElementContext context,
      final long eventElementInstanceKey,
      final ProcessInstanceRecord record) {

    elementInstanceState.storeRecord(
        eventElementInstanceKey,
        context.getElementInstanceKey(),
        record,
        ProcessInstanceIntent.ELEMENT_ACTIVATING,
        Purpose.DEFERRED);
  }

  public void publishTriggeredBoundaryEvent(final BpmnElementContext context) {
    publishTriggeredEvent(context, BpmnElementType.BOUNDARY_EVENT);
  }

  private void publishTriggeredEvent(
      final BpmnElementContext context, final BpmnElementType elementType) {
    elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
        .filter(record -> record.getValue().getBpmnElementType() == elementType)
        .filter(record -> record.getState() == ProcessInstanceIntent.ELEMENT_ACTIVATING)
        .findFirst()
        .ifPresent(
            deferredRecord ->
                publishActivatingEvent(deferredRecord.getKey(), deferredRecord.getValue()));
  }

  private void publishActivatingEvent(
      final long elementInstanceKey, final ProcessInstanceRecord eventRecord) {

    stateWriter.appendFollowUpEvent(
        elementInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, eventRecord);
  }

  public void triggerEventBasedGateway(
      final ExecutableEventBasedGateway element, final BpmnElementContext context) {

    triggerEvent(
        context,
        eventTrigger -> {
          final var triggeredEvent = getTriggeredEvent(element, context, eventTrigger);

          final var record =
              getEventRecord(
                  context.getRecordValue(), eventTrigger, triggeredEvent.getElementType());

          final var eventElementInstanceKey = keyGenerator.nextKey();
          deferActivatingEvent(context, eventElementInstanceKey, record);

          stateTransitionBehavior.transitionToCompleting(context);

          return eventElementInstanceKey;
        });
  }

  private ExecutableFlowNode getTriggeredEvent(
      final ExecutableEventBasedGateway element,
      final BpmnElementContext context,
      final EventTrigger eventTrigger) {

    return element.getOutgoing().stream()
        .map(ExecutableSequenceFlow::getTarget)
        .filter(target -> target.getId().equals(eventTrigger.getElementId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    context,
                    String.format(
                        "Expected an event attached to the event-based gateway with id '%s' but not found.",
                        bufferAsString(eventTrigger.getElementId()))));
  }

  private void triggerEvent(
      final BpmnElementContext context, final ToLongFunction<EventTrigger> eventHandler) {

    final var eventTrigger =
        eventScopeInstanceState.peekEventTrigger(context.getElementInstanceKey());

    if (eventTrigger == null) {
      // the activity (i.e. its event scope) is left - discard the event
      return;
    }

    final var eventElementInstanceKey = eventHandler.applyAsLong(eventTrigger);

    final var eventVariables = eventTrigger.getVariables();
    if (eventVariables != null && eventVariables.capacity() > 0) {
      variablesState.setTemporaryVariables(eventElementInstanceKey, eventVariables);
    }

    eventScopeInstanceState.deleteTrigger(
        context.getElementInstanceKey(), eventTrigger.getEventKey());
  }

  public void publishTriggeredEventBasedGateway(final BpmnElementContext context) {
    publishTriggeredEvent(context, BpmnElementType.INTERMEDIATE_CATCH_EVENT);
  }

  public void triggerStartEvent(final BpmnElementContext context) {
    final long processDefinitionKey = context.getProcessDefinitionKey();
    final long processInstanceKey = context.getProcessInstanceKey();

    final var process = processState.getProcessByKey(context.getProcessDefinitionKey());
    if (process == null) {
      // this should never happen because processes are never deleted.
      throw new BpmnProcessingException(
          context, String.format(NO_PROCESS_FOUND_MESSAGE, processDefinitionKey));
    }

    final var triggeredEvent = eventScopeInstanceState.peekEventTrigger(processDefinitionKey);
    if (triggeredEvent == null) {
      throw new BpmnProcessingException(
          context, String.format(NO_TRIGGERED_EVENT_MESSAGE, processDefinitionKey));
    }

    createProcessInstance(process, processInstanceKey);

    final var record =
        getEventRecord(context.getRecordValue(), triggeredEvent, BpmnElementType.START_EVENT)
            .setProcessInstanceKey(processInstanceKey)
            .setVersion(process.getVersion())
            .setBpmnProcessId(process.getBpmnProcessId())
            .setFlowScopeKey(processInstanceKey);

    final var newEventInstanceKey = keyGenerator.nextKey();
    elementInstanceState.storeRecord(
        newEventInstanceKey,
        processInstanceKey,
        record,
        ProcessInstanceIntent.ELEMENT_ACTIVATING,
        Purpose.DEFERRED);

    variablesState.setTemporaryVariables(newEventInstanceKey, triggeredEvent.getVariables());

    eventScopeInstanceState.deleteTrigger(processDefinitionKey, triggeredEvent.getEventKey());
  }

  private void createProcessInstance(final DeployedProcess process, final long processInstanceKey) {

    recordForWFICreation
        .setBpmnProcessId(process.getBpmnProcessId())
        .setProcessDefinitionKey(process.getKey())
        .setVersion(process.getVersion())
        .setProcessInstanceKey(processInstanceKey)
        .setElementId(process.getProcess().getId())
        .setBpmnElementType(process.getProcess().getElementType());

    stateWriter.appendFollowUpEvent(
        processInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, recordForWFICreation);
  }

  public boolean publishTriggeredStartEvent(final BpmnElementContext context) {

    final var deferredStartEvent =
        elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
            .filter(record -> record.getValue().getBpmnElementType() == BpmnElementType.START_EVENT)
            .filter(record -> record.getState() == ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .findFirst();

    deferredStartEvent.ifPresent(
        deferredRecord -> {
          final var elementInstanceKey = deferredRecord.getKey();

          stateWriter.appendFollowUpEvent(
              elementInstanceKey,
              ProcessInstanceIntent.ELEMENT_ACTIVATING,
              deferredRecord.getValue());
        });

    return deferredStartEvent.isPresent();
  }

  public void triggerEventSubProcess(
      final ExecutableStartEvent startEvent, final BpmnElementContext context) {

    if (stateBehavior.getFlowScopeInstance(context).getInterruptingEventKey() > 0) {
      // the flow scope is already interrupted - discard this event
      return;
    }

    final var flowScopeContext = stateBehavior.getFlowScopeContext(context);

    triggerEvent(
        flowScopeContext,
        eventTrigger -> {
          final var eventSubProcessElementId = startEvent.getEventSubProcess();
          final var record =
              getEventRecord(context.getRecordValue(), eventTrigger, BpmnElementType.SUB_PROCESS)
                  .setElementId(eventSubProcessElementId);

          final long eventElementInstanceKey = keyGenerator.nextKey();
          if (startEvent.interrupting()) {

            triggerInterruptingEventSubProcess(
                context, flowScopeContext, record, eventElementInstanceKey);

          } else {
            // activate non-interrupting event sub-process
            publishActivatingEvent(eventElementInstanceKey, record);
          }

          return eventElementInstanceKey;
        });
  }

  private void triggerInterruptingEventSubProcess(
      final BpmnElementContext context,
      final BpmnElementContext flowScopeContext,
      final ProcessInstanceRecord eventRecord,
      final long eventElementInstanceKey) {

    unsubscribeFromEvents(flowScopeContext);

    final var noActiveChildInstances =
        stateTransitionBehavior.terminateChildInstances(flowScopeContext);
    if (noActiveChildInstances) {
      // activate interrupting event sub-process
      publishActivatingEvent(eventElementInstanceKey, eventRecord);

    } else {
      // wait until child instances are terminated
      deferActivatingEvent(flowScopeContext, eventElementInstanceKey, eventRecord);
    }

    stateBehavior.updateFlowScopeInstance(
        context,
        flowScopeInstance -> {
          flowScopeInstance.spawnToken();
          flowScopeInstance.setInterruptingEventKey(eventElementInstanceKey);
        });
  }

  public void publishTriggeredEventSubProcess(final BpmnElementContext context) {
    final var elementInstance = stateBehavior.getElementInstance(context);

    if (isInterrupted(elementInstance)) {
      elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
          .filter(record -> record.getKey() == elementInstance.getInterruptingEventKey())
          .filter(record -> record.getValue().getBpmnElementType() == BpmnElementType.SUB_PROCESS)
          .findFirst()
          .ifPresent(
              record -> {
                final var elementInstanceKey = record.getKey();
                final var interruptingRecord = record.getValue();

                stateWriter.appendFollowUpEvent(
                    elementInstanceKey,
                    ProcessInstanceIntent.ELEMENT_ACTIVATING,
                    interruptingRecord);
              });
    }
  }

  private boolean isInterrupted(final ElementInstance elementInstance) {
    return elementInstance.getNumberOfActiveTokens() == 2
        && elementInstance.isInterrupted()
        && elementInstance.isActive();
  }
}
