/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn;

import static io.zeebe.protocol.Protocol.START_PARTITION_ID;
import static io.zeebe.test.util.MsgPackUtil.asMsgPack;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.zeebe.engine.processing.message.MessageObserver;
import io.zeebe.engine.util.StreamProcessorRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.util.buffer.BufferUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;
import org.mockito.ArgumentCaptor;

public final class ProcessInstanceStreamProcessorTest {

  private static final String PROCESS_ID = "process";
  private static final BpmnModelInstance SERVICE_TASK_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .sequenceFlowId("flow1")
          .serviceTask("task", b -> b.zeebeJobType("taskType"))
          .sequenceFlowId("flow2")
          .endEvent("end")
          .done();
  private static final BpmnModelInstance SUB_PROCESS_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .subProcess("subProcess")
          .embeddedSubProcess()
          .startEvent()
          .serviceTask("task", b -> b.zeebeJobType("taskType"))
          .endEvent()
          .subProcessDone()
          .endEvent()
          .done();
  private static final BpmnModelInstance MESSAGE_CATCH_EVENT_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .intermediateCatchEvent(
              "catch-event",
              c ->
                  c.message(m -> m.name("order canceled").zeebeCorrelationKeyExpression("orderId")))
          .done();
  private static final BpmnModelInstance TIMER_BOUNDARY_EVENT_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent()
          .serviceTask("task1", b -> b.zeebeJobType("type"))
          .boundaryEvent("timer1")
          .cancelActivity(true)
          .timerWithDuration("PT1S")
          .endEvent("timer1End")
          .moveToActivity("task1")
          .endEvent("end")
          .done();
  @Rule public Timeout timeoutRule = new Timeout(2, TimeUnit.MINUTES);
  private final StreamProcessorRule envRule = new StreamProcessorRule();
  private final ProcessInstanceStreamProcessorRule streamProcessorRule =
      new ProcessInstanceStreamProcessorRule(envRule);
  @Rule public RuleChain chain = RuleChain.outerRule(envRule).around(streamProcessorRule);

  @Test
  public void shouldRejectCancellationInDirectSuccession() {
    // given
    streamProcessorRule.deploy(SERVICE_TASK_PROCESS);

    final Record<ProcessInstanceRecord> createdEvent =
        streamProcessorRule.createAndReceiveProcessInstance(r -> r.setBpmnProcessId(PROCESS_ID));
    waitUntil(() -> envRule.events().onlyJobRecords().withIntent(JobIntent.CREATE).exists());

    // when
    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, createdEvent.getValue());
    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, createdEvent.getValue());

    // then
    streamProcessorRule.awaitElementInState("process", ProcessInstanceIntent.ELEMENT_TERMINATED);

    final List<ProcessInstanceIntent> processInstanceLifecycle =
        envRule.events().onlyStatesOf("process").collect(Collectors.toList());

    LifecycleAssert.assertThat(processInstanceLifecycle).compliesWithCompleteLifecycle();

    final Record<ProcessInstanceRecord> rejection =
        envRule.events().onlyProcessInstanceRecords().onlyRejections().findFirst().get();

    Assertions.assertThat(rejection)
        .hasIntent(ProcessInstanceIntent.CANCEL)
        .hasRejectionReason(
            "Expected to cancel a process instance with key '"
                + createdEvent.getKey()
                + "', but no such process was found");
  }

  @Test
  public void shouldCancelActivityInStateReady() {
    // given
    streamProcessorRule.deploy(SERVICE_TASK_PROCESS);

    final Record<ProcessInstanceRecord> createdEvent =
        streamProcessorRule.createAndReceiveProcessInstance(r -> r.setBpmnProcessId(PROCESS_ID));
    waitUntil(
        () ->
            envRule
                .events()
                .onlyProcessInstanceRecords()
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .filter(
                    w -> w.getValue().getElementIdBuffer().equals(BufferUtil.wrapString("start")))
                .exists());

    // when
    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, createdEvent.getValue());

    // then
    streamProcessorRule.awaitElementInState(PROCESS_ID, ProcessInstanceIntent.ELEMENT_TERMINATED);

    final List<Record<ProcessInstanceRecord>> records =
        envRule.events().onlyProcessInstanceRecords().collect(Collectors.toList());

    final List<ProcessInstanceIntent> processInstanceLifecycle =
        envRule.events().onlyStatesOf("process").collect(Collectors.toList());

    final List<ProcessInstanceIntent> taskLifecycle =
        envRule.events().onlyStatesOf("task").collect(Collectors.toList());

    LifecycleAssert.assertThat(processInstanceLifecycle)
        .compliesWithCompleteLifecycle()
        .endsWith(ProcessInstanceIntent.ELEMENT_TERMINATED);
    LifecycleAssert.assertThat(taskLifecycle).compliesWithCompleteLifecycle();

    ProcessInstanceAssert.assertThat(records).doesNotEvaluateFlowAfterTerminatingElement("process");
  }

  @Test
  public void shouldCancelAndCompleteJobConcurrentlyIbProcess() {
    // given
    streamProcessorRule.deploy(SUB_PROCESS_PROCESS);
    final Record<ProcessInstanceRecord> createdEvent =
        streamProcessorRule.createAndReceiveProcessInstance(r -> r.setBpmnProcessId(PROCESS_ID));
    waitUntil(() -> envRule.events().onlyJobRecords().withIntent(JobIntent.CREATE).exists());

    // when
    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, createdEvent.getValue());
    streamProcessorRule.completeFirstJob();

    // then
    streamProcessorRule.awaitElementInState("process", ProcessInstanceIntent.ELEMENT_TERMINATED);

    final List<ProcessInstanceIntent> processInstanceLifecycle =
        envRule.events().onlyStatesOf("process").collect(Collectors.toList());
    final List<ProcessInstanceIntent> subProcessLifecycle =
        envRule.events().onlyStatesOf("subProcess").collect(Collectors.toList());
    final List<ProcessInstanceIntent> taskLifecycle =
        envRule.events().onlyStatesOf("task").collect(Collectors.toList());

    LifecycleAssert.assertThat(processInstanceLifecycle)
        .compliesWithCompleteLifecycle()
        .endsWith(ProcessInstanceIntent.ELEMENT_TERMINATED);

    LifecycleAssert.assertThat(subProcessLifecycle).compliesWithCompleteLifecycle();

    LifecycleAssert.assertThat(taskLifecycle).compliesWithCompleteLifecycle();
  }

  @Test
  public void shouldRetryToOpenMessageSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);
    streamProcessorRule.createProcessInstance(
        r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    // when
    envRule
        .getClock()
        .addTime(
            MessageObserver.SUBSCRIPTION_CHECK_INTERVAL.plus(MessageObserver.SUBSCRIPTION_TIMEOUT));

    // then
    verify(streamProcessorRule.getMockSubscriptionCommandSender(), timeout(5_000).times(2))
        .openMessageSubscription(
            START_PARTITION_ID,
            catchEvent.getValue().getProcessInstanceKey(),
            catchEvent.getKey(),
            catchEvent.getValue().getBpmnProcessIdBuffer(),
            wrapString("order canceled"),
            wrapString("order-123"),
            true);
  }

  @Test
  public void shouldRejectDuplicatedOpenProcessInstanceSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);
    streamProcessorRule.createProcessInstance(
        r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    // when
    final ProcessInstanceSubscriptionRecord subscription = subscriptionRecordForEvent(catchEvent);

    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CREATE, subscription);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CREATE, subscription);

    // then
    final Record<ProcessInstanceSubscriptionRecord> rejection =
        streamProcessorRule.awaitAndGetFirstSubscriptionRejection();

    assertThat(rejection.getIntent()).isEqualTo(ProcessInstanceSubscriptionIntent.CREATE);
    assertThat(rejection.getRejectionType()).isEqualTo(RejectionType.INVALID_STATE);
  }

  @Test
  public void shouldRejectDuplicatedCorrelateProcessInstanceSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);

    streamProcessorRule.createProcessInstance(
        r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    final ProcessInstanceSubscriptionRecord subscription = subscriptionRecordForEvent(catchEvent);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CREATE, subscription);
    waitUntil(
        () ->
            envRule
                .events()
                .onlyProcessInstanceSubscriptionRecords()
                .withIntent(ProcessInstanceSubscriptionIntent.CREATED)
                .exists());

    // when
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CORRELATE, subscription);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CORRELATE, subscription);

    // then
    final Record<ProcessInstanceSubscriptionRecord> rejection =
        streamProcessorRule.awaitAndGetFirstSubscriptionRejection();

    assertThat(rejection.getIntent()).isEqualTo(ProcessInstanceSubscriptionIntent.CORRELATE);
    assertThat(rejection.getRejectionType()).isEqualTo(RejectionType.NOT_FOUND);

    final ArgumentCaptor<DirectBuffer> captor = ArgumentCaptor.forClass(DirectBuffer.class);

    verify(streamProcessorRule.getMockSubscriptionCommandSender(), timeout(5_000))
        .correlateMessageSubscription(
            eq(subscription.getSubscriptionPartitionId()),
            eq(subscription.getProcessInstanceKey()),
            eq(subscription.getElementInstanceKey()),
            eq(subscription.getBpmnProcessIdBuffer()),
            captor.capture());
    BufferUtil.equals(captor.getValue(), subscription.getMessageNameBuffer());

    verify(streamProcessorRule.getMockSubscriptionCommandSender(), timeout(5_000))
        .rejectCorrelateMessageSubscription(
            eq(subscription.getProcessInstanceKey()),
            eq(subscription.getBpmnProcessIdBuffer()),
            eq(subscription.getMessageKey()),
            captor.capture(),
            any());

    BufferUtil.equals(captor.getValue(), subscription.getMessageNameBuffer());
  }

  @Test
  public void shouldRejectCorrelateProcessInstanceSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);

    final Record<ProcessInstanceRecord> createdEvent =
        streamProcessorRule.createAndReceiveProcessInstance(
            r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    final ProcessInstanceSubscriptionRecord subscription = subscriptionRecordForEvent(catchEvent);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CREATE, subscription);

    waitUntil(
        () ->
            envRule
                .events()
                .onlyProcessInstanceSubscriptionRecords()
                .withIntent(ProcessInstanceSubscriptionIntent.CREATED)
                .exists());

    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, catchEvent.getValue());
    streamProcessorRule.awaitElementInState(PROCESS_ID, ProcessInstanceIntent.ELEMENT_TERMINATED);

    // when
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CORRELATE, subscription);

    // then
    final Record<ProcessInstanceSubscriptionRecord> rejection =
        streamProcessorRule.awaitAndGetFirstSubscriptionRejection();

    assertThat(rejection.getIntent()).isEqualTo(ProcessInstanceSubscriptionIntent.CORRELATE);
    // since we mock the message partition, we never get the acknowledged CLOSE command, so our
    // subscription remains in closing state
    assertThat(rejection.getRejectionType()).isEqualTo(RejectionType.INVALID_STATE);
  }

  @Test
  public void shouldRetryToCloseMessageSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);

    final Record<ProcessInstanceRecord> createdEvent =
        streamProcessorRule.createAndReceiveProcessInstance(
            r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    final ProcessInstanceSubscriptionRecord subscription = subscriptionRecordForEvent(catchEvent);

    envRule.writeCommand(
        createdEvent.getKey(), ProcessInstanceIntent.CANCEL, catchEvent.getValue());

    streamProcessorRule.awaitElementInState(PROCESS_ID, ProcessInstanceIntent.ELEMENT_TERMINATED);

    // when
    envRule
        .getClock()
        .addTime(
            MessageObserver.SUBSCRIPTION_CHECK_INTERVAL.plus(MessageObserver.SUBSCRIPTION_TIMEOUT));

    // then
    verify(streamProcessorRule.getMockSubscriptionCommandSender(), timeout(5_000).times(2))
        .closeMessageSubscription(
            subscription.getSubscriptionPartitionId(),
            subscription.getProcessInstanceKey(),
            subscription.getElementInstanceKey(),
            subscription.getMessageNameBuffer());
  }

  @Test
  public void shouldRejectDuplicatedCloseProcessInstanceSubscription() {
    // given
    streamProcessorRule.deploy(MESSAGE_CATCH_EVENT_PROCESS);
    streamProcessorRule.createProcessInstance(
        r -> r.setBpmnProcessId(PROCESS_ID).setVariables(asMsgPack("orderId", "order-123")));

    final Record<ProcessInstanceRecord> catchEvent =
        streamProcessorRule.awaitElementInState(
            "catch-event", ProcessInstanceIntent.ELEMENT_ACTIVATED);

    final ProcessInstanceSubscriptionRecord subscription = subscriptionRecordForEvent(catchEvent);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CREATE, subscription);

    waitUntil(
        () ->
            envRule
                .events()
                .onlyProcessInstanceSubscriptionRecords()
                .withIntent(ProcessInstanceSubscriptionIntent.CREATED)
                .exists());

    // when
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CLOSE, subscription);
    envRule.writeCommand(ProcessInstanceSubscriptionIntent.CLOSE, subscription);

    // then
    final Record<ProcessInstanceSubscriptionRecord> rejection =
        streamProcessorRule.awaitAndGetFirstSubscriptionRejection();

    assertThat(rejection.getIntent()).isEqualTo(ProcessInstanceSubscriptionIntent.CLOSE);
    assertThat(rejection.getRejectionType()).isEqualTo(RejectionType.NOT_FOUND);
  }

  @Test
  public void shouldNotTriggerBoundaryEventIfTaskIsCompleted() {
    // given
    streamProcessorRule.deploy(TIMER_BOUNDARY_EVENT_PROCESS);
    streamProcessorRule.createProcessInstance(r -> r.setBpmnProcessId(PROCESS_ID));

    // when
    final Record<TimerRecord> timerRecord =
        streamProcessorRule.awaitTimerInState("timer1", TimerIntent.CREATED);
    final Record<JobRecord> jobRecord =
        streamProcessorRule.awaitJobInState("task1", JobIntent.CREATED);

    envRule.writeCommand(jobRecord.getKey(), JobIntent.COMPLETE, jobRecord.getValue());
    envRule.writeCommand(timerRecord.getKey(), TimerIntent.TRIGGER, timerRecord.getValue());
    streamProcessorRule.awaitElementInState(PROCESS_ID, ProcessInstanceIntent.ELEMENT_COMPLETED);

    // then
    assertThat(envRule.events().onlyTimerRecords().collect(Collectors.toList()))
        .extracting(r -> tuple(r.getRecordType(), r.getIntent()))
        .containsSubsequence(
            tuple(RecordType.EVENT, TimerIntent.CREATED),
            tuple(RecordType.COMMAND, TimerIntent.TRIGGER),
            tuple(RecordType.COMMAND_REJECTION, TimerIntent.TRIGGER));
    // ensures timer1 node never exists as far as execution goes
    assertThat(
            envRule.events().onlyProcessInstanceRecords().onlyEvents().collect(Collectors.toList()))
        .noneMatch(r -> r.getValue().getElementIdBuffer().equals(wrapString("timer1")));
  }

  @Test
  public void shouldIgnoreSecondConsecutiveBoundaryEventTrigger() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .serviceTask("task", b -> b.zeebeJobType("type"))
            .boundaryEvent("timer1")
            .timerWithDuration("PT1S")
            .endEvent("timer1End")
            .moveToActivity("task")
            .boundaryEvent("timer2")
            .timerWithDuration("PT2S")
            .endEvent("timer2End")
            .done();

    streamProcessorRule.deploy(process);
    streamProcessorRule.createProcessInstance(r -> r.setBpmnProcessId(PROCESS_ID));

    // when
    final Record<TimerRecord> timer1Record =
        streamProcessorRule.awaitTimerInState("timer1", TimerIntent.CREATED);
    final Record<TimerRecord> timer2Record =
        streamProcessorRule.awaitTimerInState("timer2", TimerIntent.CREATED);

    envRule.writeCommand(timer1Record.getKey(), TimerIntent.TRIGGER, timer1Record.getValue());
    envRule.writeCommand(timer2Record.getKey(), TimerIntent.TRIGGER, timer2Record.getValue());
    streamProcessorRule.awaitElementInState(PROCESS_ID, ProcessInstanceIntent.ELEMENT_COMPLETED);

    // then
    assertThat(
            envRule
                .events()
                .onlyProcessInstanceRecords()
                .skipUntil(r -> r.getValue().getElementIdBuffer().equals(wrapString("task")))
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETING)
                .map(Record::getValue)
                .map(ProcessInstanceRecord::getElementIdBuffer)
                .map(BufferUtil::bufferAsString))
        .containsExactly("timer1", "timer1End", "process");
  }

  private ProcessInstanceSubscriptionRecord subscriptionRecordForEvent(
      final Record<ProcessInstanceRecord> catchEvent) {
    return new ProcessInstanceSubscriptionRecord()
        .setSubscriptionPartitionId(START_PARTITION_ID)
        .setProcessInstanceKey(catchEvent.getValue().getProcessInstanceKey())
        .setElementInstanceKey(catchEvent.getKey())
        .setMessageKey(3L)
        .setMessageName(wrapString("order canceled"));
  }
}
