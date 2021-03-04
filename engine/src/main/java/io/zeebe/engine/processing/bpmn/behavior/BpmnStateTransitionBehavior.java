/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.behavior;

import io.zeebe.engine.metrics.ProcessEngineMetrics;
import io.zeebe.engine.processing.bpmn.BpmnElementContainerProcessor;
import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnProcessingException;
import io.zeebe.engine.processing.bpmn.ProcessInstanceLifecycle;
import io.zeebe.engine.processing.bpmn.ProcessInstanceStateTransitionGuard;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCallActivity;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowNode;
import io.zeebe.engine.processing.deployment.model.element.ExecutableSequenceFlow;
import io.zeebe.engine.processing.streamprocessor.MigratedStreamProcessors;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.deployment.DeployedProcess;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import java.util.function.Function;

public final class BpmnStateTransitionBehavior {

  private static final String NO_PROCESS_FOUND_MESSAGE =
      "Expected to find a deployed process for process id '%s', but none found.";
  private final TypedStreamWriter streamWriter;
  private final KeyGenerator keyGenerator;
  private final BpmnStateBehavior stateBehavior;
  private final Function<BpmnElementType, BpmnElementContainerProcessor<ExecutableFlowElement>>
      processorLookUp;

  private final ProcessInstanceStateTransitionGuard stateTransitionGuard;
  private final ProcessEngineMetrics metrics;
  private final ProcessInstanceRecord childInstanceRecord = new ProcessInstanceRecord();
  private final StateWriter stateWriter;
  private final TypedCommandWriter commandWriter;

  public BpmnStateTransitionBehavior(
      final TypedStreamWriter streamWriter,
      final KeyGenerator keyGenerator,
      final BpmnStateBehavior stateBehavior,
      final ProcessEngineMetrics metrics,
      final ProcessInstanceStateTransitionGuard stateTransitionGuard,
      final Function<BpmnElementType, BpmnElementContainerProcessor<ExecutableFlowElement>>
          processorLookUp,
      final Writers writers) {
    // todo (@korthout): replace streamWriter by writers
    this.streamWriter = streamWriter;
    this.keyGenerator = keyGenerator;
    this.stateBehavior = stateBehavior;
    this.metrics = metrics;
    this.stateTransitionGuard = stateTransitionGuard;
    this.processorLookUp = processorLookUp;
    stateWriter = writers.state();
    commandWriter = writers.command();
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToActivating(final BpmnElementContext context) {
    if (MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      final var elementInstance = stateBehavior.getElementInstance(context);
      if (elementInstance != null) {
        // if the element already exists, then the Activate_Element command is processed as a result
        // of resolving an incident. We don't have to transition again. Just update the context
        return context.copy(
            context.getElementInstanceKey(),
            context.getRecordValue(),
            ProcessInstanceIntent.ELEMENT_ACTIVATING);
      }
    }
    return transitionTo(context, ProcessInstanceIntent.ELEMENT_ACTIVATING);
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToActivated(final BpmnElementContext context) {
    final BpmnElementContext transitionedContext =
        transitionTo(context, ProcessInstanceIntent.ELEMENT_ACTIVATED);
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      stateTransitionGuard.registerStateTransition(
          context, ProcessInstanceIntent.ELEMENT_ACTIVATED);
    }
    metrics.elementInstanceActivated(context.getBpmnElementType());
    return transitionedContext;
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToCompleting(final BpmnElementContext context) {
    if (MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      final var elementInstance = stateBehavior.getElementInstance(context);
      if (elementInstance.getState() == ProcessInstanceIntent.ELEMENT_COMPLETING) {
        // if the element is already completing, then the Complete_Element command is processed as a
        // result of resolving an incident. We don't have to transition again. Just update the
        // context
        return context.copy(
            context.getElementInstanceKey(),
            context.getRecordValue(),
            ProcessInstanceIntent.ELEMENT_COMPLETING);
      }
    }
    final var transitionedContext = transitionTo(context, ProcessInstanceIntent.ELEMENT_COMPLETING);
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      stateTransitionGuard.registerStateTransition(
          context, ProcessInstanceIntent.ELEMENT_COMPLETING);
    }
    return transitionedContext;
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToCompleted(final BpmnElementContext context) {
    final var transitionedContext = transitionTo(context, ProcessInstanceIntent.ELEMENT_COMPLETED);
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      stateTransitionGuard.registerStateTransition(
          context, ProcessInstanceIntent.ELEMENT_COMPLETED);
    }
    metrics.elementInstanceCompleted(context.getBpmnElementType());
    return transitionedContext;
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToTerminating(final BpmnElementContext context) {
    final var transitionedContext =
        transitionTo(context, ProcessInstanceIntent.ELEMENT_TERMINATING);
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      stateTransitionGuard.registerStateTransition(
          context, ProcessInstanceIntent.ELEMENT_TERMINATING);
    }
    return transitionedContext;
  }

  /** @return context with updated intent */
  public BpmnElementContext transitionToTerminated(final BpmnElementContext context) {
    final var transitionedContext = transitionTo(context, ProcessInstanceIntent.ELEMENT_TERMINATED);
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      stateTransitionGuard.registerStateTransition(
          context, ProcessInstanceIntent.ELEMENT_TERMINATED);
    }
    metrics.elementInstanceTerminated(context.getBpmnElementType());
    return transitionedContext;
  }

  private BpmnElementContext transitionTo(
      final BpmnElementContext context, final ProcessInstanceIntent transition) {
    final var key = context.getElementInstanceKey();
    final var value = context.getRecordValue();
    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      verifyTransition(context, transition);
      streamWriter.appendFollowUpEvent(key, transition, value);
    } else {
      stateWriter.appendFollowUpEvent(key, transition, value);
    }
    return context.copy(key, value, transition);
  }

  private void verifyTransition(
      final BpmnElementContext context, final ProcessInstanceIntent transition) {

    if (!ProcessInstanceLifecycle.canTransition(context.getIntent(), transition)) {
      throw new BpmnProcessingException(
          context,
          String.format(
              "Expected to take transition to '%s' but element instance is in state '%s'.",
              transition, context.getIntent()));
    }
  }

  public void takeSequenceFlow(
      final BpmnElementContext context, final ExecutableSequenceFlow sequenceFlow) {
    verifyTransition(context, ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN);

    final var record =
        context
            .getRecordValue()
            .setElementId(sequenceFlow.getId())
            .setBpmnElementType(sequenceFlow.getElementType());

    if (!MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      streamWriter.appendFollowUpEvent(
          keyGenerator.nextKey(), ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN, record);
      stateBehavior.spawnToken(context);
    } else {
      stateWriter.appendFollowUpEvent(
          keyGenerator.nextKey(), ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN, record);
    }
  }

  public long activateChildInstance(
      final BpmnElementContext context, final ExecutableFlowElement childElement) {

    final var childInstanceRecord =
        context
            .getRecordValue()
            .setFlowScopeKey(context.getElementInstanceKey())
            .setElementId(childElement.getId())
            .setBpmnElementType(childElement.getElementType());

    final var childInstanceKey = keyGenerator.nextKey();

    stateWriter.appendFollowUpEvent(
        childInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, childInstanceRecord);

    return childInstanceKey;
  }

  public void activateElementInstanceInFlowScope(
      final BpmnElementContext context, final ExecutableFlowElement element) {

    final var elementInstanceRecord =
        context
            .getRecordValue()
            .setFlowScopeKey(context.getFlowScopeKey())
            .setElementId(element.getId())
            .setBpmnElementType(element.getElementType());

    final var elementInstanceKey = keyGenerator.nextKey();

    if (MigratedStreamProcessors.isMigrated(element.getElementType())) {
      commandWriter.appendFollowUpCommand(
          elementInstanceKey, ProcessInstanceIntent.ACTIVATE_ELEMENT, elementInstanceRecord);
    } else {
      streamWriter.appendFollowUpEvent(
          elementInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, elementInstanceRecord);

      stateBehavior.createElementInstanceInFlowScope(
          context, elementInstanceKey, elementInstanceRecord);
    }
  }

  /**
   * Terminate all child instances of the given scope.
   *
   * @param context the scope to terminate the child instances of
   * @return {@code true} if the scope has no active child instances
   */
  public boolean terminateChildInstances(final BpmnElementContext context) {

    final var childInstances = stateBehavior.getChildInstances(context);

    for (final BpmnElementContext childInstanceContext : childInstances) {

      if (ProcessInstanceLifecycle.canTerminate(childInstanceContext.getIntent())) {
        if (!MigratedStreamProcessors.isMigrated(childInstanceContext.getBpmnElementType())) {
          transitionToTerminating(childInstanceContext);
        } else {
          commandWriter.appendFollowUpCommand(
              childInstanceContext.getElementInstanceKey(),
              ProcessInstanceIntent.TERMINATE_ELEMENT,
              childInstanceContext.getRecordValue());
        }

      } else if (childInstanceContext.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED) {
        // clean up the state because the completed event will not be processed
        stateBehavior.removeElementInstance(childInstanceContext);
      }
    }

    final var elementInstance = stateBehavior.getElementInstance(context);
    final var activeChildInstances = elementInstance.getNumberOfActiveElementInstances();

    if (activeChildInstances > 0) {
      // wait for child instances to be terminated

      // clean up the state because some events of child instances will not be processed (e.g.
      // element completed, sequence flow taken)
      final int pendingTokens = elementInstance.getNumberOfActiveTokens() - activeChildInstances;
      for (int t = 0; t < pendingTokens; t++) {
        elementInstance.consumeToken();
      }
      stateBehavior.updateElementInstance(elementInstance);
    }

    return activeChildInstances == 0;
  }

  public <T extends ExecutableFlowNode> void takeOutgoingSequenceFlows(
      final T element, final BpmnElementContext context) {

    final var outgoingSequenceFlows = element.getOutgoing();
    if (outgoingSequenceFlows.isEmpty()) {
      // behaves like an implicit end event

      // todo(zell): needs to be migrated for Call Activity, Sub Process and Multi instance!
      onElementCompleted(element, context);

    } else {
      outgoingSequenceFlows.forEach(sequenceFlow -> takeSequenceFlow(context, sequenceFlow));
    }
  }

  public void onElementCompleted(
      final ExecutableFlowElement element, final BpmnElementContext childContext) {
    final ExecutableFlowElement containerScope;
    final BpmnElementContext containerContext;
    final var flowScope = element.getFlowScope();
    if (flowScope != null) {
      containerScope = flowScope;
      containerContext = stateBehavior.getFlowScopeContext(childContext);
    } else {
      // no flowscope, assume this is called from a parent process
      containerContext = stateBehavior.getParentElementInstanceContext(childContext);
      containerScope = getParentProcessScope(containerContext, childContext);
    }
    final var containerProcessor = processorLookUp.apply(containerScope.getElementType());
    containerProcessor.onChildCompleted(containerScope, containerContext, childContext);
  }

  public void onElementTerminated(
      final ExecutableFlowElement element, final BpmnElementContext childContext) {
    final ExecutableFlowElement containerScope;
    final BpmnElementContext containerContext;
    final var flowScope = element.getFlowScope();
    if (flowScope != null) {
      containerContext = stateBehavior.getFlowScopeContext(childContext);
      containerScope = flowScope;
    } else {
      // no flowscope, assume this is called from a parent process
      containerContext = stateBehavior.getParentElementInstanceContext(childContext);
      containerScope = getParentProcessScope(containerContext, childContext);
    }
    final var containerProcessor = processorLookUp.apply(containerContext.getBpmnElementType());
    containerProcessor.onChildTerminated(containerScope, containerContext, childContext);
  }

  private ExecutableCallActivity getParentProcessScope(
      final BpmnElementContext callActivityContext, final BpmnElementContext childContext) {
    final var processDefinitionKey = callActivityContext.getProcessDefinitionKey();
    final var elementId = callActivityContext.getElementId();

    return stateBehavior
        .getProcess(processDefinitionKey)
        .map(DeployedProcess::getProcess)
        .map(
            process ->
                process.getElementById(
                    elementId, BpmnElementType.CALL_ACTIVITY, ExecutableCallActivity.class))
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    childContext, String.format(NO_PROCESS_FOUND_MESSAGE, processDefinitionKey)));
  }

  public long createChildProcessInstance(
      final DeployedProcess process, final BpmnElementContext context) {

    final var processInstanceKey = keyGenerator.nextKey();

    childInstanceRecord.reset();
    childInstanceRecord
        .setBpmnProcessId(process.getBpmnProcessId())
        .setVersion(process.getVersion())
        .setProcessDefinitionKey(process.getKey())
        .setProcessInstanceKey(processInstanceKey)
        .setParentProcessInstanceKey(context.getProcessInstanceKey())
        .setParentElementInstanceKey(context.getElementInstanceKey())
        .setElementId(process.getProcess().getId())
        .setBpmnElementType(process.getProcess().getElementType());

    streamWriter.appendFollowUpEvent(
        processInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, childInstanceRecord);

    stateBehavior.createElementInstance(processInstanceKey, childInstanceRecord);

    return processInstanceKey;
  }

  public void terminateChildProcessInstance(final BpmnElementContext context) {
    stateBehavior
        .getCalledChildInstance(context)
        .filter(ElementInstance::canTerminate)
        .map(instance -> context.copy(instance.getKey(), instance.getValue(), instance.getState()))
        .ifPresentOrElse(
            childInstanceContext -> transitionToTerminating(childInstanceContext) /* TERMINATING */,
            () -> transitionToTerminated(context) /* TERMINATED */);
  }
}
