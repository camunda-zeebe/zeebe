/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.deployment.distribute;

import io.zeebe.engine.processing.deployment.DeploymentResponder;
import io.zeebe.engine.processing.deployment.MessageStartEventSubscriptionManager;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import java.util.function.Consumer;

public final class DeploymentDistributeProcessor implements TypedRecordProcessor<DeploymentRecord> {

  private final MessageStartEventSubscriptionManager messageStartEventSubscriptionManager;
  private final DeploymentResponder deploymentResponder;
  private final int partitionId;
  private final StateWriter stateWriter;

  public DeploymentDistributeProcessor(
      final ProcessState processState,
      final DeploymentResponder deploymentResponder,
      final int partitionId,
      final Writers writers) {
    messageStartEventSubscriptionManager = new MessageStartEventSubscriptionManager(processState);
    this.deploymentResponder = deploymentResponder;
    this.partitionId = partitionId;
    stateWriter = writers.state();
  }

  @Override
  public void processRecord(
      final long position,
      final TypedRecord<DeploymentRecord> event,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    final var deploymentEvent = event.getValue();
    final var deploymentKey = event.getKey();

    stateWriter.appendFollowUpEvent(deploymentKey, DeploymentIntent.DISTRIBUTED, deploymentEvent);
    deploymentResponder.sendDeploymentResponse(deploymentKey, partitionId);

    messageStartEventSubscriptionManager.tryReOpenMessageStartEventSubscription(
        deploymentEvent, stateWriter);
  }
}
