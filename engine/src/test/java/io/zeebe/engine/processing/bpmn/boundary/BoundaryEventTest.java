/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.boundary;

import static io.zeebe.test.util.MsgPackUtil.asMsgPack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordValue;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.zeebe.protocol.record.value.VariableRecordValue;
import io.zeebe.test.util.record.ProcessInstances;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class BoundaryEventTest {
  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();
  private static final String PROCESS_ID = "process";
  private static final BpmnModelInstance MULTIPLE_SEQUENCE_FLOWS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("type"))
          .boundaryEvent("timer")
          .cancelActivity(true)
          .timerWithDurationExpression("duration(\"PT0.1S\")")
          .endEvent("end1")
          .moveToNode("timer")
          .endEvent("end2")
          .moveToActivity("task")
          .endEvent()
          .done();
  private static final BpmnModelInstance NON_INTERRUPTING_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("type"))
          .boundaryEvent("event")
          .cancelActivity(false)
          .timerWithCycleExpression("cycle(duration(\"PT1S\"))")
          .endEvent()
          .done();

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  @Test
  public void shouldTakeAllOutgoingSequenceFlowsIfTriggered() {
    // given
    ENGINE.deployment().withXmlResource(MULTIPLE_SEQUENCE_FLOWS).deploy();
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // when
    RecordingExporter.timerRecords()
        .withHandlerNodeId("timer")
        .withIntent(TimerIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();
    ENGINE.increaseTime(Duration.ofMinutes(1));

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withElementType(BpmnElementType.END_EVENT)
                .withProcessInstanceKey(processInstanceKey)
                .limit(2))
        .extracting(Record::getValue)
        .extracting(ProcessInstanceRecordValue::getElementId)
        .contains("end1", "end2");
  }

  @Test
  public void shouldActivateBoundaryEventWhenEventTriggered() {
    // given
    ENGINE.deployment().withXmlResource(MULTIPLE_SEQUENCE_FLOWS).deploy();
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    RecordingExporter.timerRecords()
        .withHandlerNodeId("timer")
        .withIntent(TimerIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();

    RecordingExporter.jobRecords(JobIntent.CREATED)
        .withType("type")
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();

    // when
    ENGINE.increaseTime(Duration.ofMinutes(1));

    // then
    final List<Record<RecordValue>> records =
        RecordingExporter.records()
            .limitToProcessInstance(processInstanceKey)
            .limit(
                r ->
                    r.getValue() instanceof ProcessInstanceRecord
                        && ((ProcessInstanceRecord) r.getValue()).getElementId().equals("timer")
                        && r.getIntent() == ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .asList();

    assertThat(records)
        .extracting(Record::getValueType, Record::getIntent)
        .containsSequence(
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.EVENT_OCCURRED),
            tuple(ValueType.TIMER, TimerIntent.TRIGGERED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATING),
            tuple(ValueType.JOB, JobIntent.CANCEL),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATED),
            tuple(ValueType.JOB, JobIntent.CANCELED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  @Test
  public void shouldApplyOutputMappingOnTriggering() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .serviceTask("task", b -> b.zeebeJobType("type"))
            .boundaryEvent("event")
            .message(m -> m.name("message").zeebeCorrelationKeyExpression("key"))
            .zeebeOutputExpression("foo", "bar")
            .endEvent("endTimer")
            .moveToActivity("task")
            .endEvent()
            .done();
    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("key", "123").create();

    // when
    ENGINE
        .message()
        .withName("message")
        .withCorrelationKey("123")
        .withVariables(asMsgPack("foo", 3))
        .publish();

    // then
    final Record<VariableRecordValue> variableEvent =
        RecordingExporter.variableRecords()
            .withProcessInstanceKey(processInstanceKey)
            .withName("bar")
            .getFirst();
    Assertions.assertThat(variableEvent.getValue()).hasValue("3");
  }

  @Test
  public void shouldUseScopeVariablesWhenApplyingOutputMappings() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .serviceTask("task", b -> b.zeebeJobType("type").zeebeInputExpression("oof", "baz"))
            .boundaryEvent("timer")
            .cancelActivity(true)
            .timerWithDuration("PT1S")
            .endEvent("endTimer")
            .moveToActivity("task")
            .endEvent()
            .done();
    ENGINE.deployment().withXmlResource(process).deploy();
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(PROCESS_ID)
            .withVariables("{ \"foo\": 1, \"oof\": 2 }")
            .create();

    // when
    RecordingExporter.timerRecords()
        .withHandlerNodeId("timer")
        .withIntent(TimerIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();
    ENGINE.increaseTime(Duration.ofMinutes(1));

    // then
    final Record<ProcessInstanceRecordValue> boundaryTriggered =
        RecordingExporter.processInstanceRecords()
            .withElementId("timer")
            .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();
    final Map<String, String> variables =
        ProcessInstances.getCurrentVariables(processInstanceKey, boundaryTriggered.getPosition());
    assertThat(variables).contains(entry("foo", "1"), entry("oof", "2"));
  }

  @Test
  public void shouldTerminateSubProcessBeforeTriggeringBoundaryEvent() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .subProcess("sub")
            .embeddedSubProcess()
            .startEvent()
            .serviceTask("task", t -> t.zeebeJobType("type"))
            .endEvent()
            .subProcessDone()
            .boundaryEvent("timer")
            .cancelActivity(true)
            .timerWithDuration("PT1S")
            .endEvent("endTimer")
            .moveToActivity("sub")
            .endEvent()
            .done();
    ENGINE.deployment().withXmlResource(process).deploy();
    ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // when
    RecordingExporter.jobRecords().withIntent(JobIntent.CREATED).getFirst();
    ENGINE.increaseTime(Duration.ofMinutes(1));

    final List<Record<RecordValue>> records =
        RecordingExporter.records()
            .limit(
                r ->
                    r.getValue() instanceof ProcessInstanceRecord
                        && ((ProcessInstanceRecord) r.getValue()).getElementId().equals("timer")
                        && r.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED)
            .asList();

    assertThat(records)
        .extracting(Record::getValueType, Record::getIntent)
        .endsWith(
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.EVENT_OCCURRED),
            tuple(ValueType.TIMER, TimerIntent.TRIGGERED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATING),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATING),
            tuple(ValueType.JOB, JobIntent.CANCEL),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATED),
            tuple(ValueType.JOB, JobIntent.CANCELED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATING),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETING),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldNotTerminateActivityForNonInterruptingBoundaryEvents() {
    // given
    ENGINE.deployment().withXmlResource(NON_INTERRUPTING_PROCESS).deploy();
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // when
    RecordingExporter.jobRecords()
        .withType("type")
        .withIntent(JobIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();
    ENGINE.increaseTime(Duration.ofSeconds(1));
    RecordingExporter.timerRecords()
        .withHandlerNodeId("event")
        .withIntent(TimerIntent.TRIGGER)
        .withProcessInstanceKey(processInstanceKey)
        .getFirst();
    ENGINE.job().ofInstance(processInstanceKey).withType("type").complete();

    // then
    final List<Record<RecordValue>> records =
        RecordingExporter.records()
            .limit(
                r ->
                    r.getValue() instanceof ProcessInstanceRecord
                        && ((ProcessInstanceRecord) r.getValue()).getElementId().equals("task")
                        && r.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED)
            .asList();

    assertThat(records)
        .extracting(Record::getValueType, Record::getIntent)
        .containsSubsequence(
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.EVENT_OCCURRED),
            tuple(ValueType.TIMER, TimerIntent.TRIGGERED),
            tuple(ValueType.TIMER, TimerIntent.CREATED),
            tuple(ValueType.JOB, JobIntent.COMPLETED),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETING),
            tuple(ValueType.TIMER, TimerIntent.CANCEL),
            tuple(ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldUseScopeToExtractCorrelationKeys() {
    // given
    final String processId = "shouldHaveScopeKeyIfBoundaryEvent";
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .serviceTask("task", c -> c.zeebeJobType("type").zeebeInputExpression("bar", "foo"))
            .boundaryEvent(
                "event",
                b -> b.message(m -> m.zeebeCorrelationKeyExpression("foo").name("message")))
            .endEvent()
            .moveToActivity("task")
            .endEvent()
            .done();
    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(processId)
            .withVariables("{'foo':1,'bar':2}")
            .create();
    ENGINE.message().withName("message").withCorrelationKey("1").publish();

    // then
    // if correlation key was extracted from the task, then foo in the task scope would be 2 and
    // no event occurred would be published
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withElementId("task")
                .withProcessInstanceKey(processInstanceKey)
                .withIntent(ProcessInstanceIntent.EVENT_OCCURRED)
                .getFirst())
        .isNotNull();
  }

  @Test
  public void shouldHaveScopeKeyIfBoundaryEvent() {
    // given
    final String processId = "shouldHaveScopeKeyIfBoundaryEvent";
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .serviceTask("task", c -> c.zeebeJobType("type"))
            .boundaryEvent(
                "event",
                b -> b.message(m -> m.zeebeCorrelationKeyExpression("orderId").name("message")))
            .endEvent()
            .moveToActivity("task")
            .endEvent()
            .done();
    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("orderId", true).create();
    final Record<ProcessInstanceRecordValue> failureEvent =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("task")
            .getFirst();

    // then
    final Record<IncidentRecordValue> incidentRecord =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    Assertions.assertThat(incidentRecord.getValue())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasProcessInstanceKey(processInstanceKey)
        .hasElementId("task")
        .hasElementInstanceKey(failureEvent.getKey())
        .hasJobKey(-1L)
        .hasVariableScopeKey(processInstanceKey);
  }
}
