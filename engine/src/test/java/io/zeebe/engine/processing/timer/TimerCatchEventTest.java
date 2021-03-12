/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.timer;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.zeebe.protocol.record.value.TimerRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class TimerCatchEventTest {
  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();
  private static final BpmnModelInstance SINGLE_TIMER_PROCESS =
      Bpmn.createExecutableProcess("SINGLE_TIMER_PROCESS")
          .startEvent()
          .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT0.1S"))
          .endEvent()
          .done();
  private static final BpmnModelInstance BOUNDARY_EVENT_PROCESS =
      Bpmn.createExecutableProcess("BOUNDARY_EVENT_PROCESS")
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("type"))
          .boundaryEvent("timer")
          .cancelActivity(true)
          .timerWithDuration("PT1S")
          .endEvent("eventEnd")
          .moveToActivity("task")
          .endEvent("taskEnd")
          .done();
  private static final BpmnModelInstance TWO_REPS_CYCLE_PROCESS =
      Bpmn.createExecutableProcess("TWO_REPS_CYCLE_PROCESS")
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("type"))
          .boundaryEvent("timer")
          .cancelActivity(false)
          .timerWithCycle("R2/PT1S")
          .endEvent()
          .moveToNode("task")
          .endEvent()
          .done();
  private static final BpmnModelInstance INFINITE_CYCLE_PROCESS =
      Bpmn.createExecutableProcess("INFINITE_CYCLE_PROCESS")
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("type"))
          .boundaryEvent("timer")
          .cancelActivity(false)
          .timerWithCycle("R/PT1S")
          .endEvent()
          .moveToNode("task")
          .endEvent()
          .done();

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  @BeforeClass
  public static void init() {
    ENGINE.deployment().withXmlResource(SINGLE_TIMER_PROCESS).deploy();
    ENGINE.deployment().withXmlResource(BOUNDARY_EVENT_PROCESS).deploy();
    ENGINE.deployment().withXmlResource(TWO_REPS_CYCLE_PROCESS).deploy();
    ENGINE.deployment().withXmlResource(INFINITE_CYCLE_PROCESS).deploy();
  }

  @Test
  public void testLifeCycle() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("testLifeCycle")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT0S"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("testLifeCycle").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .withElementId("timer")
                .limit(5))
        .extracting(Record::getIntent)
        .containsExactly(
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.EVENT_OCCURRED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED);

    assertThat(
            RecordingExporter.records().limitToProcessInstance(processInstanceKey).timerRecords())
        .extracting(Record::getIntent)
        .containsSubsequence(TimerIntent.CREATED, TimerIntent.TRIGGER, TimerIntent.TRIGGERED);
  }

  @Test
  public void shouldCreateTimer() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldCreateTimer")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT10S"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldCreateTimer").create();

    // when
    final Record<ProcessInstanceRecordValue> activatedEvent =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("timer")
            .getFirst();

    // then
    final Record<TimerRecordValue> createdEvent =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    Assertions.assertThat(createdEvent.getValue())
        .hasElementInstanceKey(activatedEvent.getKey())
        .hasProcessInstanceKey(processInstanceKey);

    assertThat(createdEvent.getValue().getDueDate())
        .isBetween(
            ENGINE.getClock().getCurrentTimeInMillis(),
            createdEvent.getTimestamp() + Duration.ofSeconds(10).toMillis());
  }

  @Test
  public void shouldCreateTimerFromFeelExpression() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldCreateTimer")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDurationExpression("\"PT10S\""))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldCreateTimer").create();

    // when
    final Record<ProcessInstanceRecordValue> activatedEvent =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("timer")
            .getFirst();

    // then
    final Record<TimerRecordValue> createdEvent =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    Assertions.assertThat(createdEvent.getValue())
        .hasElementInstanceKey(activatedEvent.getKey())
        .hasProcessInstanceKey(processInstanceKey);

    assertThat(createdEvent.getValue().getDueDate())
        .isBetween(
            ENGINE.getClock().getCurrentTimeInMillis(),
            createdEvent.getTimestamp() + Duration.ofSeconds(10).toMillis());
  }

  @Test
  public void shouldTriggerTimer() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("SINGLE_TIMER_PROCESS").create();

    // when
    final Record<TimerRecordValue> createdEvent =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    ENGINE.increaseTime(Duration.ofSeconds(1));

    // then
    final Record<TimerRecordValue> triggeredEvent =
        RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertThat(triggeredEvent.getKey()).isEqualTo(createdEvent.getKey());
    assertThat(triggeredEvent.getValue()).isEqualTo(createdEvent.getValue());
    assertThat(Duration.ofMillis(triggeredEvent.getTimestamp() - createdEvent.getTimestamp()))
        .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
  }

  @Test
  public void shouldCompleteTimerEvent() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("SINGLE_TIMER_PROCESS").create();

    // when
    RecordingExporter.timerRecords(TimerIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();

    ENGINE.increaseTime(Duration.ofSeconds(1));

    // then
    final Record<ProcessInstanceRecordValue> activatedEvent =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("timer")
            .withIntent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .getFirst();

    final Record<ProcessInstanceRecordValue> completedEvent =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("timer")
            .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
            .getFirst();

    assertThat(completedEvent.getKey()).isEqualTo(activatedEvent.getKey());
    assertThat(completedEvent.getValue()).isEqualTo(activatedEvent.getValue());
  }

  @Test
  public void shouldTriggerTimerWithZeroDuration() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldTriggerTimerWithZeroDuration")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT0S"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldTriggerTimerWithZeroDuration").create();

    // then
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
                .withProcessInstanceKey(processInstanceKey)
                .exists())
        .isTrue();
  }

  @Test
  public void shouldTriggerTimerWithNegativeDuration() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldTriggerTimerWithNegativeDuration")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDuration("-PT1H"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldTriggerTimerWithNegativeDuration").create();

    // then
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
                .withProcessInstanceKey(processInstanceKey)
                .exists())
        .isTrue();
  }

  @Test
  public void shouldTriggerMultipleTimers() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldTriggerMultipleTimers")
            .startEvent()
            .parallelGateway()
            .intermediateCatchEvent("timer1", c -> c.timerWithDuration("PT1S"))
            .endEvent()
            .moveToLastGateway()
            .intermediateCatchEvent("timer2", c -> c.timerWithDuration("PT2S"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldTriggerMultipleTimers").create();

    // when
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(2))
        .hasSize(2);

    ENGINE.increaseTime(Duration.ofSeconds(1));

    final Record<TimerRecordValue> triggeredTimer1 =
        RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
            .withHandlerNodeId("timer1")
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    ENGINE.increaseTime(Duration.ofSeconds(1));

    final Record<TimerRecordValue> triggeredTimer2 =
        RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
            .withHandlerNodeId("timer2")
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .withElementType(BpmnElementType.INTERMEDIATE_CATCH_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .limit(2))
        .extracting(r -> r.getValue().getElementId())
        .contains("timer1", "timer2");

    final long timer1DueDate = triggeredTimer1.getValue().getDueDate();
    assertThat(triggeredTimer2.getValue().getDueDate())
        .isBetween(timer1DueDate, timer1DueDate + Duration.ofSeconds(1).toMillis());
  }

  @Test
  public void shouldCancelTimer() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldCancelTimer")
            .startEvent()
            .intermediateCatchEvent("timer", c -> c.timerWithDuration("PT10S"))
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("shouldCancelTimer").create();

    // when
    final Record<TimerRecordValue> createdEvent =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withHandlerNodeId("timer")
            .getFirst();

    ENGINE.processInstance().withInstanceKey(processInstanceKey).cancel();

    // then
    final Record<TimerRecordValue> canceledEvent =
        RecordingExporter.timerRecords(TimerIntent.CANCELED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertThat(canceledEvent.getKey()).isEqualTo(createdEvent.getKey());
    assertThat(canceledEvent.getValue()).isEqualTo(createdEvent.getValue());
  }

  @Test
  public void shouldCreateTimerBasedOnBoundaryEvent() {
    // given/when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("BOUNDARY_EVENT_PROCESS").create();

    // then
    final Record<TimerRecordValue> timerRecord =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    final Record<ProcessInstanceRecordValue> activityRecord =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("task")
            .getFirst();

    Assertions.assertThat(timerRecord.getValue())
        .hasElementInstanceKey(activityRecord.getKey())
        .hasTargetElementId("timer");

    assertThat(timerRecord.getValue().getDueDate())
        .isBetween(
            ENGINE.getClock().getCurrentTimeInMillis(),
            timerRecord.getTimestamp() + Duration.ofSeconds(1).toMillis());
  }

  @Test
  public void shouldTriggerHandlerNodeWhenAttachedToActivity() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("BOUNDARY_EVENT_PROCESS").create();

    // when
    final Record<TimerRecordValue> timerCreated =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    ENGINE.increaseTime(Duration.ofSeconds(1));

    // then
    final Record<TimerRecordValue> timerTriggered =
        RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertThat(timerTriggered.getKey()).isEqualTo(timerCreated.getKey());
    assertThat(timerTriggered.getValue()).isEqualTo(timerCreated.getValue());

    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETING)
                .withProcessInstanceKey(processInstanceKey)
                .withElementId("timer")
                .exists())
        .isTrue();
  }

  @Test
  public void shouldRecreateTimerWithCycle() {
    // given
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("TWO_REPS_CYCLE_PROCESS").create();

    // when
    final Record<TimerRecordValue> timerCreated =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    ENGINE.increaseTime(Duration.ofSeconds(1));

    final Record<TimerRecordValue> timerRescheduled =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .limit(2)
            .getLast();

    // then
    assertThat(timerRescheduled.getKey()).isGreaterThan(timerCreated.getKey());
    Assertions.assertThat(timerRescheduled.getValue())
        .hasTargetElementId(timerCreated.getValue().getTargetElementId())
        .hasElementInstanceKey(timerCreated.getValue().getElementInstanceKey());

    assertThat(timerRescheduled.getValue().getDueDate())
        .isGreaterThanOrEqualTo(
            timerCreated.getValue().getDueDate() + Duration.ofSeconds(1).toMillis());
  }

  @Test
  public void shouldRecreateTimerForTheSpecifiedAmountOfRepetitions() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("shouldRecreateTimerForTheSpecifiedAmountOfRepetitions")
            .startEvent()
            .parallelGateway("gw")
            .serviceTask("task-1", b -> b.zeebeJobType("type"))
            .boundaryEvent("timer-1")
            .cancelActivity(false)
            .timerWithCycle("R1/PT1S")
            .endEvent()
            .moveToNode("gw")
            .serviceTask("task-2", b -> b.zeebeJobType("type"))
            .boundaryEvent("timer-2")
            .cancelActivity(false)
            .timerWithCycle("R3/PT1S")
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId("shouldRecreateTimerForTheSpecifiedAmountOfRepetitions")
            .create();

    // when
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(2))
        .hasSize(2);

    ENGINE.increaseTime(Duration.ofSeconds(1));

    assertThat(
            RecordingExporter.timerRecords(TimerIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(3))
        .hasSize(3);

    ENGINE.increaseTime(Duration.ofSeconds(1));

    // then
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(4))
        .hasSize(4)
        .extracting(r -> r.getValue().getTargetElementId())
        .containsExactlyInAnyOrder("timer-1", "timer-2", "timer-2", "timer-2");
  }

  @Test
  public void shouldRecreateTimerInfinitely() {
    // given
    final int expectedRepetitions = 10;

    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId("INFINITE_CYCLE_PROCESS").create();

    // when
    IntStream.range(1, expectedRepetitions + 1)
        .forEach(
            i -> {
              RecordingExporter.timerRecords(TimerIntent.CREATED)
                  .withProcessInstanceKey(processInstanceKey)
                  .limit(i)
                  .count();

              ENGINE.increaseTime(Duration.ofSeconds(1));
            });

    // then
    assertThat(
            RecordingExporter.timerRecords(TimerIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(expectedRepetitions))
        .hasSize(expectedRepetitions);

    assertThat(
            RecordingExporter.timerRecords(TimerIntent.TRIGGERED)
                .withProcessInstanceKey(processInstanceKey)
                .limit(expectedRepetitions))
        .hasSize(expectedRepetitions);
  }
}
