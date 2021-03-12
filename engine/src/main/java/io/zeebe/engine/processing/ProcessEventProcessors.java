/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing;

import io.zeebe.engine.processing.bpmn.BpmnStreamProcessor;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.message.CloseProcessInstanceSubscription;
import io.zeebe.engine.processing.message.CorrelateProcessInstanceSubscription;
import io.zeebe.engine.processing.message.ProcessInstanceSubscriptionCreateProcessor;
import io.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.zeebe.engine.processing.processinstance.CreateProcessInstanceProcessor;
import io.zeebe.engine.processing.processinstance.CreateProcessInstanceWithResultProcessor;
import io.zeebe.engine.processing.processinstance.ProcessInstanceCommandProcessor;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessors;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.processing.timer.CancelTimerProcessor;
import io.zeebe.engine.processing.timer.DueDateTimerChecker;
import io.zeebe.engine.processing.timer.TriggerTimerProcessor;
import io.zeebe.engine.processing.variable.UpdateVariableDocumentProcessor;
import io.zeebe.engine.processing.variable.VariableBehavior;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableProcessInstanceSubscriptionState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.ProcessInstanceCreationIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.intent.VariableDocumentIntent;
import java.util.Arrays;

public final class ProcessEventProcessors {

  public static TypedRecordProcessor<ProcessInstanceRecord> addProcessProcessors(
      final ZeebeState zeebeState,
      final ExpressionProcessor expressionProcessor,
      final TypedRecordProcessors typedRecordProcessors,
      final SubscriptionCommandSender subscriptionCommandSender,
      final CatchEventBehavior catchEventBehavior,
      final DueDateTimerChecker timerChecker,
      final Writers writers) {
    final MutableProcessInstanceSubscriptionState subscriptionState =
        zeebeState.getProcessInstanceSubscriptionState();
    final VariableBehavior variableBehavior =
        new VariableBehavior(
            zeebeState.getVariableState(), writers.state(), zeebeState.getKeyGenerator());

    addProcessInstanceCommandProcessor(typedRecordProcessors, zeebeState.getElementInstanceState());

    final var bpmnStreamProcessor =
        new BpmnStreamProcessor(
            expressionProcessor, catchEventBehavior, variableBehavior, zeebeState, writers);
    addBpmnStepProcessor(typedRecordProcessors, bpmnStreamProcessor);

    addMessageStreamProcessors(
        typedRecordProcessors, subscriptionState, subscriptionCommandSender, zeebeState, writers);
    addTimerStreamProcessors(
        typedRecordProcessors, timerChecker, zeebeState, catchEventBehavior, expressionProcessor);
    addVariableDocumentStreamProcessors(
        typedRecordProcessors,
        variableBehavior,
        zeebeState.getElementInstanceState(),
        writers.state());
    addProcessInstanceCreationStreamProcessors(
        typedRecordProcessors, zeebeState, writers, variableBehavior);

    return bpmnStreamProcessor;
  }

  private static void addProcessInstanceCommandProcessor(
      final TypedRecordProcessors typedRecordProcessors,
      final MutableElementInstanceState elementInstanceState) {

    final ProcessInstanceCommandProcessor commandProcessor =
        new ProcessInstanceCommandProcessor(elementInstanceState);

    Arrays.stream(ProcessInstanceIntent.values())
        .filter(ProcessInstanceIntent::isProcessInstanceCommand)
        .forEach(
            intent ->
                typedRecordProcessors.onCommand(
                    ValueType.PROCESS_INSTANCE, intent, commandProcessor));
  }

  private static void addBpmnStepProcessor(
      final TypedRecordProcessors typedRecordProcessors,
      final BpmnStreamProcessor bpmnStepProcessor) {

    Arrays.stream(ProcessInstanceIntent.values())
        .filter(ProcessInstanceIntent::isBpmnElementCommand)
        .forEach(
            intent ->
                typedRecordProcessors.onCommand(
                    ValueType.PROCESS_INSTANCE, intent, bpmnStepProcessor));

    Arrays.stream(ProcessInstanceIntent.values())
        .filter(ProcessInstanceIntent::isBpmnElementEvent)
        .forEach(
            intent ->
                typedRecordProcessors.onEvent(
                    ValueType.PROCESS_INSTANCE, intent, bpmnStepProcessor));
  }

  private static void addMessageStreamProcessors(
      final TypedRecordProcessors typedRecordProcessors,
      final MutableProcessInstanceSubscriptionState subscriptionState,
      final SubscriptionCommandSender subscriptionCommandSender,
      final ZeebeState zeebeState,
      final Writers writers) {
    typedRecordProcessors
        .onCommand(
            ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
            ProcessInstanceSubscriptionIntent.CREATE,
            new ProcessInstanceSubscriptionCreateProcessor(
                zeebeState.getProcessInstanceSubscriptionState(), writers))
        .onCommand(
            ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
            ProcessInstanceSubscriptionIntent.CORRELATE,
            new CorrelateProcessInstanceSubscription(
                subscriptionState, subscriptionCommandSender, zeebeState))
        .onCommand(
            ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
            ProcessInstanceSubscriptionIntent.CLOSE,
            new CloseProcessInstanceSubscription(subscriptionState));
  }

  private static void addTimerStreamProcessors(
      final TypedRecordProcessors typedRecordProcessors,
      final DueDateTimerChecker timerChecker,
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventOutput,
      final ExpressionProcessor expressionProcessor) {

    typedRecordProcessors
        .onCommand(
            ValueType.TIMER,
            TimerIntent.TRIGGER,
            new TriggerTimerProcessor(zeebeState, catchEventOutput, expressionProcessor))
        .onCommand(
            ValueType.TIMER,
            TimerIntent.CANCEL,
            new CancelTimerProcessor(zeebeState.getTimerState()))
        .withListener(timerChecker);
  }

  private static void addVariableDocumentStreamProcessors(
      final TypedRecordProcessors typedRecordProcessors,
      final VariableBehavior variableBehavior,
      final ElementInstanceState elementInstanceState,
      final StateWriter stateWriter) {
    typedRecordProcessors.onCommand(
        ValueType.VARIABLE_DOCUMENT,
        VariableDocumentIntent.UPDATE,
        new UpdateVariableDocumentProcessor(elementInstanceState, variableBehavior, stateWriter));
  }

  private static void addProcessInstanceCreationStreamProcessors(
      final TypedRecordProcessors typedRecordProcessors,
      final ZeebeState zeebeState,
      final Writers writers,
      final VariableBehavior variableBehavior) {
    final MutableElementInstanceState elementInstanceState = zeebeState.getElementInstanceState();
    final KeyGenerator keyGenerator = zeebeState.getKeyGenerator();

    final CreateProcessInstanceProcessor createProcessor =
        new CreateProcessInstanceProcessor(
            zeebeState.getProcessState(),
            elementInstanceState,
            keyGenerator,
            writers,
            variableBehavior);
    typedRecordProcessors.onCommand(
        ValueType.PROCESS_INSTANCE_CREATION, ProcessInstanceCreationIntent.CREATE, createProcessor);

    typedRecordProcessors.onCommand(
        ValueType.PROCESS_INSTANCE_CREATION,
        ProcessInstanceCreationIntent.CREATE_WITH_AWAITING_RESULT,
        new CreateProcessInstanceWithResultProcessor(createProcessor, elementInstanceState));
  }
}
