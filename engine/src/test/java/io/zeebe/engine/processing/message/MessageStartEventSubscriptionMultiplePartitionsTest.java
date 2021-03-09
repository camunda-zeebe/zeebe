/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.protocol.record.value.MessageStartEventSubscriptionRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public final class MessageStartEventSubscriptionMultiplePartitionsTest {
  private static final String MESSAGE_NAME1 = "startMessage1";
  private static final String EVENT_ID1 = "startEventId1";

  public @Rule final EngineRule engine = EngineRule.multiplePartition(3);

  @Test
  public void shouldOpenMessageStartEventSubscriptionOnAllPartitions() {
    // when
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    // then
    final List<Record<MessageStartEventSubscriptionRecordValue>> subscriptions =
        RecordingExporter.messageStartEventSubscriptionRecords(
                MessageStartEventSubscriptionIntent.CREATED)
            .limit(3)
            .asList();

    assertThat(subscriptions)
        .hasSize(3)
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getStartEventId(), v.getMessageName()))
        .containsOnly(tuple(EVENT_ID1, MESSAGE_NAME1));

    final List<Integer> partitionIds = engine.getPartitionIds();
    assertThat(subscriptions)
        .extracting(Record::getPartitionId)
        .containsExactlyInAnyOrderElementsOf(partitionIds);
  }

  private static BpmnModelInstance createProcessWithOneMessageStartEvent() {
    return Bpmn.createExecutableProcess("processId")
        .startEvent(EVENT_ID1)
        .message(m -> m.name(MESSAGE_NAME1).id("startmsgId"))
        .endEvent()
        .done();
  }
}
