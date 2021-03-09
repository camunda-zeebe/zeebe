/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.zeebe.engine.processing.common.EventHandle;
import io.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.immutable.MessageStartEventSubscriptionState;
import io.zeebe.engine.state.immutable.MessageState;
import io.zeebe.engine.state.immutable.MessageSubscriptionState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableMessageState;
import io.zeebe.engine.state.mutable.MutableMessageSubscriptionState;
import io.zeebe.protocol.impl.record.value.message.MessageRecord;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.MessageIntent;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.protocol.record.intent.MessageSubscriptionIntent;
import java.util.function.Consumer;

public final class MessagePublishProcessor implements TypedRecordProcessor<MessageRecord> {

  private static final String ALREADY_PUBLISHED_MESSAGE =
      "Expected to publish a new message with id '%s', but a message with that id was already published";

  private final MessageState messageState;
  private final MessageSubscriptionState subscriptionState;
  private final MessageStartEventSubscriptionState startEventSubscriptionState;
  private final SubscriptionCommandSender commandSender;
  private final KeyGenerator keyGenerator;
  private final StateWriter stateWriter;

  private final EventHandle eventHandle;
  private final Subscriptions correlatingSubscriptions = new Subscriptions();

  private TypedResponseWriter responseWriter;
  private MessageRecord messageRecord;
  private long messageKey;

  public MessagePublishProcessor(
      final MutableMessageState messageState,
      final MutableMessageSubscriptionState subscriptionState,
      final MessageStartEventSubscriptionState startEventSubscriptionState,
      final MutableEventScopeInstanceState eventScopeInstanceState,
      final SubscriptionCommandSender commandSender,
      final KeyGenerator keyGenerator,
      final Writers writers) {
    this.messageState = messageState;
    this.subscriptionState = subscriptionState;
    this.startEventSubscriptionState = startEventSubscriptionState;
    this.commandSender = commandSender;
    this.keyGenerator = keyGenerator;
    stateWriter = writers.state();

    eventHandle = new EventHandle(keyGenerator, eventScopeInstanceState);
  }

  @Override
  public void processRecord(
      final TypedRecord<MessageRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    this.responseWriter = responseWriter;
    messageRecord = command.getValue();

    correlatingSubscriptions.clear();

    if (messageRecord.hasMessageId()
        && messageState.exist(
            messageRecord.getNameBuffer(),
            messageRecord.getCorrelationKeyBuffer(),
            messageRecord.getMessageIdBuffer())) {
      final String rejectionReason =
          String.format(
              ALREADY_PUBLISHED_MESSAGE, bufferAsString(messageRecord.getMessageIdBuffer()));

      streamWriter.appendRejection(command, RejectionType.ALREADY_EXISTS, rejectionReason);
      responseWriter.writeRejectionOnCommand(
          command, RejectionType.ALREADY_EXISTS, rejectionReason);
    } else {
      handleNewMessage(command, responseWriter, streamWriter, sideEffect);
    }
  }

  private void handleNewMessage(
      final TypedRecord<MessageRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    messageKey = keyGenerator.nextKey();

    // calculate the deadline based on the command's timestamp
    messageRecord.setDeadline(command.getTimestamp() + messageRecord.getTimeToLive());

    stateWriter.appendFollowUpEvent(messageKey, MessageIntent.PUBLISHED, command.getValue());
    responseWriter.writeEventOnCommand(
        messageKey, MessageIntent.PUBLISHED, command.getValue(), command);

    correlateToSubscriptions(messageKey, messageRecord);
    correlateToMessageStartEvents(messageRecord, streamWriter);

    sideEffect.accept(this::sendCorrelateCommand);

    if (messageRecord.getTimeToLive() <= 0L) {
      // avoid that the message can be correlated again by writing the EXPIRED event as a follow-up
      stateWriter.appendFollowUpEvent(messageKey, MessageIntent.EXPIRED, messageRecord);
    }
  }

  private void correlateToSubscriptions(final long messageKey, final MessageRecord message) {
    subscriptionState.visitSubscriptions(
        message.getNameBuffer(),
        message.getCorrelationKeyBuffer(),
        subscription -> {

          // correlate the message only once per process
          if (!subscription.isCorrelating()
              && !correlatingSubscriptions.contains(
                  subscription.getRecord().getBpmnProcessIdBuffer())) {

            final var correlatingSubscription =
                subscription
                    .getRecord()
                    .setMessageKey(messageKey)
                    .setVariables(message.getVariablesBuffer());

            stateWriter.appendFollowUpEvent(
                subscription.getKey(),
                MessageSubscriptionIntent.CORRELATING,
                correlatingSubscription);

            correlatingSubscriptions.add(correlatingSubscription);
          }

          return true;
        });
  }

  private void correlateToMessageStartEvents(
      final MessageRecord messageRecord, final TypedStreamWriter streamWriter) {

    startEventSubscriptionState.visitSubscriptionsByMessageName(
        messageRecord.getNameBuffer(),
        subscription -> {
          final var bpmnProcessIdBuffer = subscription.getBpmnProcessIdBuffer();
          final var correlationKeyBuffer = messageRecord.getCorrelationKeyBuffer();

          // create only one instance of a process per correlation key
          // - allow multiple instance if correlation key is empty
          if (!correlatingSubscriptions.contains(bpmnProcessIdBuffer)
              && (correlationKeyBuffer.capacity() == 0
                  || !messageState.existActiveProcessInstance(
                      bpmnProcessIdBuffer, correlationKeyBuffer))) {

            correlatingSubscriptions.add(subscription);

            final var processInstanceKey = keyGenerator.nextKey();

            subscription
                .setProcessInstanceKey(processInstanceKey)
                .setCorrelationKey(correlationKeyBuffer)
                .setMessageKey(messageKey)
                .setVariables(messageRecord.getVariablesBuffer());

            // TODO (saig0): the subscription should have a key (#2805)
            stateWriter.appendFollowUpEvent(
                -1L, MessageStartEventSubscriptionIntent.CORRELATED, subscription);

            eventHandle.activateStartEvent(
                streamWriter,
                subscription.getProcessDefinitionKey(),
                processInstanceKey,
                subscription.getStartEventIdBuffer());
          }
        });
  }

  private boolean sendCorrelateCommand() {

    final var success =
        correlatingSubscriptions.visitSubscriptions(
            subscription ->
                commandSender.correlateProcessInstanceSubscription(
                    subscription.getProcessInstanceKey(),
                    subscription.getElementInstanceKey(),
                    subscription.getBpmnProcessId(),
                    messageRecord.getNameBuffer(),
                    messageKey,
                    messageRecord.getVariablesBuffer(),
                    messageRecord.getCorrelationKeyBuffer()));

    return success && responseWriter.flush();
  }
}
