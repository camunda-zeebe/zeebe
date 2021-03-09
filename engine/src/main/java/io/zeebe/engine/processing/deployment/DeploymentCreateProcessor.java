/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.deployment;

import static io.zeebe.engine.state.instance.TimerInstance.NO_ELEMENT_INSTANCE;

import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.distribute.DeploymentDistributionBehavior;
import io.zeebe.engine.processing.deployment.distribute.DeploymentDistributor;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.deployment.transform.DeploymentTransformer;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.immutable.TimerInstanceState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.deployment.ProcessRecord;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.util.Either;
import io.zeebe.util.sched.ActorControl;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

public final class DeploymentCreateProcessor implements TypedRecordProcessor<DeploymentRecord> {

  private static final String COULD_NOT_CREATE_TIMER_MESSAGE =
      "Expected to create timer for start event, but encountered the following error: %s";

  private final DeploymentTransformer deploymentTransformer;
  private final ProcessState processState;
  private final TimerInstanceState timerInstanceState;
  private final CatchEventBehavior catchEventBehavior;
  private final KeyGenerator keyGenerator;
  private final ExpressionProcessor expressionProcessor;
  private final StateWriter stateWriter;
  private final MessageStartEventSubscriptionManager messageStartEventSubscriptionManager;
  private final DeploymentDistributionBehavior deploymentDistributionBehavior;

  public DeploymentCreateProcessor(
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventBehavior,
      final ExpressionProcessor expressionProcessor,
      final int partitionsCount,
      final Writers writers,
      final ActorControl actor,
      final DeploymentDistributor deploymentDistributor) {
    processState = zeebeState.getProcessState();
    timerInstanceState = zeebeState.getTimerState();
    keyGenerator = zeebeState.getKeyGenerator();
    stateWriter = writers.state();
    deploymentTransformer = new DeploymentTransformer(stateWriter, zeebeState, expressionProcessor);
    this.catchEventBehavior = catchEventBehavior;
    this.expressionProcessor = expressionProcessor;
    messageStartEventSubscriptionManager = new MessageStartEventSubscriptionManager(processState);
    deploymentDistributionBehavior =
        new DeploymentDistributionBehavior(writers, partitionsCount, deploymentDistributor, actor);
  }

  @Override
  public void processRecord(
      final TypedRecord<DeploymentRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    final DeploymentRecord deploymentEvent = command.getValue();

    final boolean accepted = deploymentTransformer.transform(deploymentEvent);
    if (accepted) {
      final long key = keyGenerator.nextKey();

      try {
        createTimerIfTimerStartEvent(command, streamWriter);
      } catch (final RuntimeException e) {
        final String reason = String.format(COULD_NOT_CREATE_TIMER_MESSAGE, e.getMessage());
        responseWriter.writeRejectionOnCommand(command, RejectionType.PROCESSING_ERROR, reason);
        streamWriter.appendRejection(command, RejectionType.PROCESSING_ERROR, reason);
        return;
      }

      responseWriter.writeEventOnCommand(key, DeploymentIntent.CREATED, deploymentEvent, command);
      stateWriter.appendFollowUpEvent(key, DeploymentIntent.CREATED, deploymentEvent);

      deploymentDistributionBehavior.distributeDeployment(deploymentEvent, key);
      messageStartEventSubscriptionManager.tryReOpenMessageStartEventSubscription(
          deploymentEvent, stateWriter);

    } else {
      responseWriter.writeRejectionOnCommand(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
      streamWriter.appendRejection(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
    }
  }

  private void createTimerIfTimerStartEvent(
      final TypedRecord<DeploymentRecord> record, final TypedStreamWriter streamWriter) {
    for (final ProcessRecord processRecord : record.getValue().processes()) {
      final List<ExecutableStartEvent> startEvents =
          processState.getProcessByKey(processRecord.getKey()).getProcess().getStartEvents();

      unsubscribeFromPreviousTimers(streamWriter, processRecord);

      for (final ExecutableCatchEventElement startEvent : startEvents) {
        if (startEvent.isTimer()) {
          // There are no variables when there is no process instance yet,
          // we use a negative scope key to indicate this
          final long scopeKey = -1L;
          final Either<Failure, Timer> timerOrError =
              startEvent.getTimerFactory().apply(expressionProcessor, scopeKey);
          if (timerOrError.isLeft()) {
            // todo(#4323): deal with this exceptional case without throwing an exception
            throw new EvaluationException(timerOrError.getLeft().getMessage());
          }

          catchEventBehavior.subscribeToTimerEvent(
              NO_ELEMENT_INSTANCE,
              NO_ELEMENT_INSTANCE,
              processRecord.getKey(),
              startEvent.getId(),
              timerOrError.get(),
              streamWriter);
        }
      }
    }
  }

  private void unsubscribeFromPreviousTimers(
      final TypedStreamWriter streamWriter, final ProcessRecord processRecord) {
    timerInstanceState.forEachTimerForElementInstance(
        NO_ELEMENT_INSTANCE,
        timer -> unsubscribeFromPreviousTimer(streamWriter, processRecord, timer));
  }

  private void unsubscribeFromPreviousTimer(
      final TypedStreamWriter streamWriter,
      final ProcessRecord processRecord,
      final TimerInstance timer) {
    final DirectBuffer timerBpmnId =
        processState.getProcessByKey(timer.getProcessDefinitionKey()).getBpmnProcessId();

    if (timerBpmnId.equals(processRecord.getBpmnProcessIdBuffer())) {
      catchEventBehavior.unsubscribeFromTimerEvent(timer, streamWriter);
    }
  }
}
