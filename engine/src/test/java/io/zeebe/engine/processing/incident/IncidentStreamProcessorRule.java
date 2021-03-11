/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.incident;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.zeebe.el.ExpressionLanguageFactory;
import io.zeebe.engine.processing.ProcessEventProcessors;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.job.JobEventProcessors;
import io.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.zeebe.engine.processing.timer.DueDateTimerChecker;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.mutable.MutableProcessState;
import io.zeebe.engine.util.StreamProcessorRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.instance.Process;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceCreationRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.ProcessInstanceCreationIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.util.buffer.BufferUtil;
import java.io.ByteArrayOutputStream;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Rule;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

public final class IncidentStreamProcessorRule extends ExternalResource {

  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private final StreamProcessorRule environmentRule;
  private SubscriptionCommandSender mockSubscriptionCommandSender;
  private DueDateTimerChecker mockTimerEventScheduler;

  private MutableProcessState processState;
  private ZeebeState zeebeState;

  public IncidentStreamProcessorRule(final StreamProcessorRule streamProcessorRule) {
    environmentRule = streamProcessorRule;
  }

  @Override
  protected void before() {
    mockSubscriptionCommandSender = mock(SubscriptionCommandSender.class);
    mockTimerEventScheduler = mock(DueDateTimerChecker.class);

    when(mockSubscriptionCommandSender.openMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(), any(), any(), anyBoolean()))
        .thenReturn(true);
    when(mockSubscriptionCommandSender.correlateMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(), any()))
        .thenReturn(true);
    when(mockSubscriptionCommandSender.closeMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(DirectBuffer.class)))
        .thenReturn(true);

    environmentRule.startTypedStreamProcessor(
        (typedRecordProcessors, processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          processState = zeebeState.getProcessState();

          final var variablesState = zeebeState.getVariableState();
          final ExpressionProcessor expressionProcessor =
              new ExpressionProcessor(
                  ExpressionLanguageFactory.createExpressionLanguage(),
                  variablesState::getVariable);

          final var writers = processingContext.getWriters();
          final var stepProcessor =
              ProcessEventProcessors.addProcessProcessors(
                  zeebeState,
                  expressionProcessor,
                  typedRecordProcessors,
                  mockSubscriptionCommandSender,
                  new CatchEventBehavior(
                      zeebeState,
                      expressionProcessor,
                      mockSubscriptionCommandSender,
                      writers.state(),
                      1),
                  mockTimerEventScheduler,
                  writers);

          JobEventProcessors.addJobProcessors(
              typedRecordProcessors, zeebeState, type -> {}, Integer.MAX_VALUE, writers);

          IncidentEventProcessors.addProcessors(typedRecordProcessors, zeebeState, stepProcessor);

          return typedRecordProcessors;
        });
  }

  public ZeebeState getZeebeState() {
    return zeebeState;
  }

  public void deploy(final BpmnModelInstance modelInstance) {
    final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(outStream, modelInstance);
    final DirectBuffer xmlBuffer = new UnsafeBuffer(outStream.toByteArray());

    final DeploymentRecord record = new DeploymentRecord();
    final DirectBuffer resourceName = wrapString("resourceName");

    final Process process = modelInstance.getModelElementsByType(Process.class).iterator().next();

    record.resources().add().setResource(xmlBuffer).setResourceName(resourceName);

    record
        .processes()
        .add()
        .setKey(1)
        .setResourceName(resourceName)
        .setBpmnProcessId(BufferUtil.wrapString(process.getId()))
        .setVersion(1);

    processState.putDeployment(record);
  }

  public Record<ProcessInstanceRecord> createProcessInstance(final String processId) {
    return createProcessInstance(processId, wrapString(""));
  }

  public Record<ProcessInstanceRecord> createProcessInstance(
      final String processId, final DirectBuffer variables) {
    environmentRule.writeCommand(
        ProcessInstanceCreationIntent.CREATE,
        processInstanceCreationRecord(BufferUtil.wrapString(processId), variables));
    final Record<ProcessInstanceRecord> createdEvent =
        awaitAndGetFirstRecordInState(ProcessInstanceIntent.ELEMENT_ACTIVATING);
    return createdEvent;
  }

  private static ProcessInstanceCreationRecord processInstanceCreationRecord(
      final DirectBuffer processId, final DirectBuffer variables) {
    final ProcessInstanceCreationRecord record = new ProcessInstanceCreationRecord();

    record.setProcessDefinitionKey(1);
    record.setBpmnProcessId(processId);
    record.setVariables(variables);

    return record;
  }

  private void awaitFirstRecordInState(final Intent state) {
    waitUntil(() -> environmentRule.events().withIntent(state).findFirst().isPresent());
  }

  private Record<ProcessInstanceRecord> awaitAndGetFirstRecordInState(
      final ProcessInstanceIntent state) {
    awaitFirstRecordInState(state);
    return environmentRule
        .events()
        .onlyProcessInstanceRecords()
        .withIntent(state)
        .findFirst()
        .get();
  }

  public void awaitIncidentInState(final Intent state) {
    waitUntil(
        () ->
            environmentRule
                .events()
                .onlyIncidentRecords()
                .onlyEvents()
                .withIntent(state)
                .findFirst()
                .isPresent());
  }

  public void awaitIncidentRejection(final Intent state) {
    waitUntil(
        () ->
            environmentRule
                .events()
                .onlyIncidentRecords()
                .onlyRejections()
                .withIntent(state)
                .findFirst()
                .isPresent());
  }
}
