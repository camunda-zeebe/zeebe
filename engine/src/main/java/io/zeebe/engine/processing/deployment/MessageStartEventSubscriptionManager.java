/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.deployment;

import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableMessage;
import io.zeebe.engine.processing.deployment.model.element.ExecutableProcess;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.state.deployment.DeployedProcess;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.deployment.ProcessRecord;
import io.zeebe.protocol.impl.record.value.message.MessageStartEventSubscriptionRecord;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.util.buffer.BufferUtil;
import java.util.List;

public class MessageStartEventSubscriptionManager {

  private final ProcessState processState;
  private final MessageStartEventSubscriptionRecord subscriptionRecord =
      new MessageStartEventSubscriptionRecord();

  public MessageStartEventSubscriptionManager(final ProcessState processState) {
    this.processState = processState;
  }

  public void tryReOpenMessageStartEventSubscription(
      final DeploymentRecord deploymentRecord, final StateWriter stateWriter) {

    for (final ProcessRecord processRecord : deploymentRecord.processes()) {
      if (isLatestProcess(processRecord)) {
        closeExistingMessageStartEventSubscriptions(processRecord, stateWriter);
        openMessageStartEventSubscriptions(processRecord, stateWriter);
      }
    }
  }

  private boolean isLatestProcess(final ProcessRecord processRecord) {
    return processState
            .getLatestProcessVersionByProcessId(processRecord.getBpmnProcessIdBuffer())
            .getVersion()
        == processRecord.getVersion();
  }

  private void closeExistingMessageStartEventSubscriptions(
      final ProcessRecord processRecord, final StateWriter stateWriter) {
    final DeployedProcess lastMsgProcess = findLastMessageStartProcess(processRecord);
    if (lastMsgProcess == null) {
      return;
    }

    subscriptionRecord.reset();
    subscriptionRecord.setProcessDefinitionKey(lastMsgProcess.getKey());

    // TODO (saig0): we should write one DELETED event for each created subscription (#2805)
    stateWriter.appendFollowUpEvent(
        -1L, MessageStartEventSubscriptionIntent.DELETED, subscriptionRecord);
  }

  private DeployedProcess findLastMessageStartProcess(final ProcessRecord processRecord) {
    for (int version = processRecord.getVersion() - 1; version > 0; --version) {
      final DeployedProcess lastMsgProcess =
          processState.getProcessByProcessIdAndVersion(
              processRecord.getBpmnProcessIdBuffer(), version);
      if (lastMsgProcess != null
          && lastMsgProcess.getProcess().getStartEvents().stream()
              .anyMatch(ExecutableCatchEventElement::isMessage)) {
        return lastMsgProcess;
      }
    }

    return null;
  }

  private void openMessageStartEventSubscriptions(
      final ProcessRecord processRecord, final StateWriter stateWriter) {
    final long processDefinitionKey = processRecord.getKey();
    final DeployedProcess processDefinition = processState.getProcessByKey(processDefinitionKey);
    final ExecutableProcess process = processDefinition.getProcess();
    final List<ExecutableStartEvent> startEvents = process.getStartEvents();

    // if startEvents contain message events
    for (final ExecutableCatchEventElement startEvent : startEvents) {
      if (startEvent.isMessage()) {
        final ExecutableMessage message = startEvent.getMessage();

        message
            .getMessageName()
            .map(BufferUtil::wrapString)
            .ifPresent(
                messageNameBuffer -> {
                  subscriptionRecord.reset();
                  subscriptionRecord
                      .setMessageName(messageNameBuffer)
                      .setProcessDefinitionKey(processDefinitionKey)
                      .setBpmnProcessId(process.getId())
                      .setStartEventId(startEvent.getId());

                  // TODO (saig0): the subscription should have a key (#2805)
                  stateWriter.appendFollowUpEvent(
                      -1L, MessageStartEventSubscriptionIntent.CREATED, subscriptionRecord);
                });
      }
    }
  }
}
