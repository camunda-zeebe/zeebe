/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message.command;

import io.zeebe.logstreams.log.LogStreamRecordWriter;
import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.value.message.MessageSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.MessageSubscriptionIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class SubscriptionCommandMessageHandler
    implements Function<byte[], CompletableFuture<Void>> {

  private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();

  private final OpenMessageSubscriptionCommand openMessageSubscriptionCommand =
      new OpenMessageSubscriptionCommand();

  private final OpenProcessInstanceSubscriptionCommand openProcessInstanceSubscriptionCommand =
      new OpenProcessInstanceSubscriptionCommand();

  private final CorrelateProcessInstanceSubscriptionCommand
      correlateProcessInstanceSubscriptionCommand =
          new CorrelateProcessInstanceSubscriptionCommand();

  private final CorrelateMessageSubscriptionCommand correlateMessageSubscriptionCommand =
      new CorrelateMessageSubscriptionCommand();

  private final CloseMessageSubscriptionCommand closeMessageSubscriptionCommand =
      new CloseMessageSubscriptionCommand();

  private final CloseProcessInstanceSubscriptionCommand closeProcessInstanceSubscriptionCommand =
      new CloseProcessInstanceSubscriptionCommand();

  private final RejectCorrelateMessageSubscriptionCommand resetMessageCorrelationCommand =
      new RejectCorrelateMessageSubscriptionCommand();

  private final RecordMetadata recordMetadata = new RecordMetadata();

  private final MessageSubscriptionRecord messageSubscriptionRecord =
      new MessageSubscriptionRecord();

  private final ProcessInstanceSubscriptionRecord processInstanceSubscriptionRecord =
      new ProcessInstanceSubscriptionRecord();

  private final Consumer<Runnable> enviromentToRun;
  private final IntFunction<LogStreamRecordWriter> logstreamRecordWriterSupplier;

  public SubscriptionCommandMessageHandler(
      final Consumer<Runnable> enviromentToRun,
      final IntFunction<LogStreamRecordWriter> logstreamRecordWriterSupplier) {
    this.enviromentToRun = enviromentToRun;
    this.logstreamRecordWriterSupplier = logstreamRecordWriterSupplier;
  }

  @Override
  public CompletableFuture<Void> apply(final byte[] bytes) {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    enviromentToRun.accept(
        () -> {
          final DirectBuffer buffer = new UnsafeBuffer(bytes);
          final int offset = 0;
          final int length = buffer.capacity();
          messageHeaderDecoder.wrap(buffer, offset);

          if (messageHeaderDecoder.schemaId() == OpenMessageSubscriptionDecoder.SCHEMA_ID) {

            switch (messageHeaderDecoder.templateId()) {
              case OpenMessageSubscriptionDecoder.TEMPLATE_ID:
                onOpenMessageSubscription(buffer, offset, length);
                break;
              case OpenProcessInstanceSubscriptionDecoder.TEMPLATE_ID:
                onOpenProcessInstanceSubscription(buffer, offset, length);
                break;
              case CorrelateProcessInstanceSubscriptionDecoder.TEMPLATE_ID:
                onCorrelateProcessInstanceSubscription(buffer, offset, length);
                break;
              case CorrelateMessageSubscriptionDecoder.TEMPLATE_ID:
                onCorrelateMessageSubscription(buffer, offset, length);
                break;
              case CloseMessageSubscriptionDecoder.TEMPLATE_ID:
                onCloseMessageSubscription(buffer, offset, length);
                break;
              case CloseProcessInstanceSubscriptionDecoder.TEMPLATE_ID:
                onCloseProcessInstanceSubscription(buffer, offset, length);
                break;
              case RejectCorrelateMessageSubscriptionDecoder.TEMPLATE_ID:
                onRejectCorrelateMessageSubscription(buffer, offset, length);
                break;
              default:
                break;
            }
          }
          future.complete(null);
        });
    return future;
  }

  private boolean onOpenMessageSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    openMessageSubscriptionCommand.wrap(buffer, offset, length);

    messageSubscriptionRecord
        .setProcessInstanceKey(openMessageSubscriptionCommand.getProcessInstanceKey())
        .setElementInstanceKey(openMessageSubscriptionCommand.getElementInstanceKey())
        .setBpmnProcessId(openMessageSubscriptionCommand.getBpmnProcessId())
        .setMessageKey(-1)
        .setMessageName(openMessageSubscriptionCommand.getMessageName())
        .setCorrelationKey(openMessageSubscriptionCommand.getCorrelationKey())
        .setInterrupting(openMessageSubscriptionCommand.shouldCloseOnCorrelate());

    return writeCommand(
        openMessageSubscriptionCommand.getSubscriptionPartitionId(),
        ValueType.MESSAGE_SUBSCRIPTION,
        MessageSubscriptionIntent.CREATE,
        messageSubscriptionRecord);
  }

  private boolean onOpenProcessInstanceSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    openProcessInstanceSubscriptionCommand.wrap(buffer, offset, length);

    final long processInstanceKey = openProcessInstanceSubscriptionCommand.getProcessInstanceKey();
    final int processInstancePartitionId = Protocol.decodePartitionId(processInstanceKey);

    processInstanceSubscriptionRecord.reset();
    processInstanceSubscriptionRecord
        .setSubscriptionPartitionId(
            openProcessInstanceSubscriptionCommand.getSubscriptionPartitionId())
        .setProcessInstanceKey(processInstanceKey)
        .setElementInstanceKey(openProcessInstanceSubscriptionCommand.getElementInstanceKey())
        .setMessageKey(-1)
        .setMessageName(openProcessInstanceSubscriptionCommand.getMessageName())
        .setInterrupting(openProcessInstanceSubscriptionCommand.shouldCloseOnCorrelate());

    return writeCommand(
        processInstancePartitionId,
        ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
        ProcessInstanceSubscriptionIntent.CREATE,
        processInstanceSubscriptionRecord);
  }

  private boolean onCorrelateProcessInstanceSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    correlateProcessInstanceSubscriptionCommand.wrap(buffer, offset, length);

    final long processInstanceKey =
        correlateProcessInstanceSubscriptionCommand.getProcessInstanceKey();
    final int processInstancePartitionId = Protocol.decodePartitionId(processInstanceKey);

    processInstanceSubscriptionRecord
        .setSubscriptionPartitionId(
            correlateProcessInstanceSubscriptionCommand.getSubscriptionPartitionId())
        .setProcessInstanceKey(processInstanceKey)
        .setElementInstanceKey(correlateProcessInstanceSubscriptionCommand.getElementInstanceKey())
        .setBpmnProcessId(correlateProcessInstanceSubscriptionCommand.getBpmnProcessId())
        .setMessageKey(correlateProcessInstanceSubscriptionCommand.getMessageKey())
        .setMessageName(correlateProcessInstanceSubscriptionCommand.getMessageName())
        .setVariables(correlateProcessInstanceSubscriptionCommand.getVariables())
        .setCorrelationKey(correlateProcessInstanceSubscriptionCommand.getCorrelationKey());

    return writeCommand(
        processInstancePartitionId,
        ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
        ProcessInstanceSubscriptionIntent.CORRELATE,
        processInstanceSubscriptionRecord);
  }

  private boolean onCorrelateMessageSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    correlateMessageSubscriptionCommand.wrap(buffer, offset, length);

    messageSubscriptionRecord.reset();
    messageSubscriptionRecord
        .setProcessInstanceKey(correlateMessageSubscriptionCommand.getProcessInstanceKey())
        .setElementInstanceKey(correlateMessageSubscriptionCommand.getElementInstanceKey())
        .setBpmnProcessId(correlateMessageSubscriptionCommand.getBpmnProcessId())
        .setMessageKey(-1)
        .setMessageName(correlateMessageSubscriptionCommand.getMessageName());

    return writeCommand(
        correlateMessageSubscriptionCommand.getSubscriptionPartitionId(),
        ValueType.MESSAGE_SUBSCRIPTION,
        MessageSubscriptionIntent.CORRELATE,
        messageSubscriptionRecord);
  }

  private boolean onCloseMessageSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    closeMessageSubscriptionCommand.wrap(buffer, offset, length);

    messageSubscriptionRecord.reset();
    messageSubscriptionRecord
        .setProcessInstanceKey(closeMessageSubscriptionCommand.getProcessInstanceKey())
        .setElementInstanceKey(closeMessageSubscriptionCommand.getElementInstanceKey())
        .setMessageKey(-1L)
        .setMessageName(closeMessageSubscriptionCommand.getMessageName());

    return writeCommand(
        closeMessageSubscriptionCommand.getSubscriptionPartitionId(),
        ValueType.MESSAGE_SUBSCRIPTION,
        MessageSubscriptionIntent.DELETE,
        messageSubscriptionRecord);
  }

  private boolean onCloseProcessInstanceSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    closeProcessInstanceSubscriptionCommand.wrap(buffer, offset, length);

    final long processInstanceKey = closeProcessInstanceSubscriptionCommand.getProcessInstanceKey();
    final int processInstancePartitionId = Protocol.decodePartitionId(processInstanceKey);

    processInstanceSubscriptionRecord.reset();
    processInstanceSubscriptionRecord
        .setSubscriptionPartitionId(
            closeProcessInstanceSubscriptionCommand.getSubscriptionPartitionId())
        .setProcessInstanceKey(processInstanceKey)
        .setElementInstanceKey(closeProcessInstanceSubscriptionCommand.getElementInstanceKey())
        .setMessageKey(-1)
        .setMessageName(closeProcessInstanceSubscriptionCommand.getMessageName());

    return writeCommand(
        processInstancePartitionId,
        ValueType.PROCESS_INSTANCE_SUBSCRIPTION,
        ProcessInstanceSubscriptionIntent.CLOSE,
        processInstanceSubscriptionRecord);
  }

  private boolean onRejectCorrelateMessageSubscription(
      final DirectBuffer buffer, final int offset, final int length) {
    resetMessageCorrelationCommand.wrap(buffer, offset, length);

    final long processInstanceKey = resetMessageCorrelationCommand.getProcessInstanceKey();

    messageSubscriptionRecord.reset();
    messageSubscriptionRecord
        .setProcessInstanceKey(processInstanceKey)
        .setElementInstanceKey(-1L)
        .setBpmnProcessId(resetMessageCorrelationCommand.getBpmnProcessId())
        .setMessageName(resetMessageCorrelationCommand.getMessageName())
        .setCorrelationKey(resetMessageCorrelationCommand.getCorrelationKey())
        .setMessageKey(resetMessageCorrelationCommand.getMessageKey())
        .setInterrupting(false);

    return writeCommand(
        resetMessageCorrelationCommand.getSubscriptionPartitionId(),
        ValueType.MESSAGE_SUBSCRIPTION,
        MessageSubscriptionIntent.REJECT,
        messageSubscriptionRecord);
  }

  private boolean writeCommand(
      final int partitionId,
      final ValueType valueType,
      final Intent intent,
      final UnpackedObject command) {

    final LogStreamRecordWriter logStreamRecordWriter =
        logstreamRecordWriterSupplier.apply(partitionId);
    if (logStreamRecordWriter == null) {
      // ignore message if you are not the leader of the partition
      return true;
    }

    logStreamRecordWriter.reset();
    recordMetadata.reset().recordType(RecordType.COMMAND).valueType(valueType).intent(intent);

    final long position =
        logStreamRecordWriter
            .key(-1)
            .metadataWriter(recordMetadata)
            .valueWriter(command)
            .tryWrite();

    return position > 0;
  }
}
