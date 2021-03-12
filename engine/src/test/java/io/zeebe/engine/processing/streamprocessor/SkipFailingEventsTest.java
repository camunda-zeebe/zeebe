/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.streamprocessor;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.zeebe.engine.processing.streamprocessor.writers.CommandResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.state.DefaultZeebeDbFactory;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.util.MockTypedRecord;
import io.zeebe.engine.util.RecordStream;
import io.zeebe.engine.util.Records;
import io.zeebe.engine.util.TestStreams;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.util.SynchronousLogStream;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.error.ErrorRecord;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.protocol.record.intent.ErrorIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.test.util.TestUtil;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public final class SkipFailingEventsTest {
  public static final String STREAM_NAME = "foo";

  public final TemporaryFolder tempFolder = new TemporaryFolder();
  public final AutoCloseableRule closeables = new AutoCloseableRule();

  public final ActorSchedulerRule actorSchedulerRule = new ActorSchedulerRule();

  @Rule
  public RuleChain ruleChain =
      RuleChain.outerRule(tempFolder).around(actorSchedulerRule).around(closeables);

  protected TestStreams streams;
  protected SynchronousLogStream stream;

  @Mock protected CommandResponseWriter commandResponseWriter;
  private KeyGenerator keyGenerator;
  private ZeebeState zeebeState;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    streams = new TestStreams(tempFolder, closeables, actorSchedulerRule.get());
    commandResponseWriter = streams.getMockedResponseWriter();
    stream = streams.createLogStream(STREAM_NAME);

    final AtomicLong key = new AtomicLong();
    keyGenerator = () -> key.getAndIncrement();
  }

  @Test
  public void shouldWriteErrorEvent() {
    // given
    final ErrorProneProcessor errorProneProcessor = new ErrorProneProcessor();

    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .onEvent(
                  ValueType.PROCESS_INSTANCE,
                  ProcessInstanceIntent.ELEMENT_ACTIVATING,
                  errorProneProcessor);
        });

    final long failingEventPosition =
        streams
            .newRecord(STREAM_NAME)
            .event(Records.processInstance(1))
            .recordType(RecordType.EVENT)
            .intent(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .key(keyGenerator.nextKey())
            .write();

    // when
    waitForRecordWhichSatisfies(e -> Records.isEvent(e, ValueType.ERROR, ErrorIntent.CREATED));

    // then
    assertThat(errorProneProcessor.getProcessCount()).isEqualTo(1);

    final ErrorRecord errorRecord =
        new RecordStream(streams.events(STREAM_NAME)).onlyErrorRecords().getFirst().getValue();

    assertThat(errorRecord.getErrorEventPosition()).isEqualTo(failingEventPosition);
    assertThat(BufferUtil.bufferAsString(errorRecord.getExceptionMessageBuffer()))
        .isEqualTo("expected");
    assertThat(errorRecord.getProcessInstanceKey()).isEqualTo(1);
  }

  @Test
  public void shouldWriteErrorEventWithNoMessage() {
    // given
    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .onEvent(
                  ValueType.PROCESS_INSTANCE,
                  ProcessInstanceIntent.ELEMENT_ACTIVATING,
                  new TypedRecordProcessor<>() {
                    @Override
                    public void processRecord(
                        final TypedRecord<UnifiedRecordValue> record,
                        final TypedResponseWriter responseWriter,
                        final TypedStreamWriter streamWriter) {
                      throw new NullPointerException();
                    }
                  });
        });

    final long failingEventPosition =
        streams
            .newRecord(STREAM_NAME)
            .event(Records.processInstance(1))
            .recordType(RecordType.EVENT)
            .intent(ProcessInstanceIntent.ELEMENT_ACTIVATING)
            .key(keyGenerator.nextKey())
            .write();

    // when
    waitForRecordWhichSatisfies(e -> Records.isEvent(e, ValueType.ERROR, ErrorIntent.CREATED));

    // then
    final ErrorRecord errorRecord =
        new RecordStream(streams.events(STREAM_NAME)).onlyErrorRecords().getFirst().getValue();

    assertThat(errorRecord.getErrorEventPosition()).isEqualTo(failingEventPosition);
    assertThat(BufferUtil.bufferAsString(errorRecord.getExceptionMessageBuffer()))
        .isEqualTo("Without exception message.");
    assertThat(errorRecord.getProcessInstanceKey()).isEqualTo(1);
  }

  @Test
  public void shouldBlacklistInstance() {
    // given
    final DumpProcessor dumpProcessor = spy(new DumpProcessor());
    final ErrorProneProcessor processor = new ErrorProneProcessor();

    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .onEvent(
                  ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATING, processor)
              .onEvent(
                  ValueType.PROCESS_INSTANCE,
                  ProcessInstanceIntent.ELEMENT_ACTIVATED,
                  dumpProcessor);
        });

    streams
        .newRecord(STREAM_NAME)
        .event(Records.processInstance(1))
        .recordType(RecordType.EVENT)
        .intent(ProcessInstanceIntent.ELEMENT_ACTIVATING)
        .key(keyGenerator.nextKey())
        .write();
    streams
        .newRecord(STREAM_NAME)
        .event(Records.processInstance(1))
        .recordType(RecordType.EVENT)
        .intent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .key(keyGenerator.nextKey())
        .write();

    // other instance
    streams
        .newRecord(STREAM_NAME)
        .event(Records.processInstance(2))
        .recordType(RecordType.EVENT)
        .intent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .key(keyGenerator.nextKey())
        .write();

    // when
    waitForRecordWhichSatisfies(
        e ->
            Records.isEvent(
                e, ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETED));

    // then
    assertThat(processor.getProcessCount()).isEqualTo(1);

    final RecordMetadata metadata = new RecordMetadata();
    metadata.valueType(ValueType.PROCESS_INSTANCE);
    final MockTypedRecord<ProcessInstanceRecord> mockTypedRecord =
        new MockTypedRecord<>(0, metadata, Records.processInstance(1));
    Assertions.assertThat(zeebeState.getBlackListState().isOnBlacklist(mockTypedRecord)).isTrue();

    verify(dumpProcessor, times(1)).processRecord(any(), any(), any(), any());
    assertThat(dumpProcessor.processedInstances).containsExactly(2L);
  }

  @Test
  public void shouldFindFailedEventsOnReprocessing() throws Exception {
    // given
    when(commandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);

    final long failedPos =
        streams
            .newRecord(STREAM_NAME)
            .event(Records.job(1))
            .recordType(RecordType.EVENT)
            .intent(JobIntent.ACTIVATED)
            .key(keyGenerator.nextKey())
            .write();
    streams
        .newRecord(STREAM_NAME)
        .event(Records.error(1, failedPos))
        .recordType(RecordType.EVENT)
        .sourceRecordPosition(failedPos)
        .intent(ErrorIntent.CREATED)
        .key(keyGenerator.nextKey())
        .write();

    final CountDownLatch latch = new CountDownLatch(1);
    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .withListener(
                  new StreamProcessorLifecycleAware() {
                    @Override
                    public void onRecovered(final ReadonlyProcessingContext ctx) {
                      latch.countDown();
                    }
                  })
              .onEvent(ValueType.JOB, JobIntent.ACTIVATED, new DumpProcessor());
        });

    // when
    latch.await(2000, TimeUnit.MILLISECONDS);

    // then
    final RecordMetadata metadata = new RecordMetadata();
    metadata.valueType(ValueType.PROCESS_INSTANCE);
    final MockTypedRecord<ProcessInstanceRecord> mockTypedRecord =
        new MockTypedRecord<>(0, metadata, Records.processInstance(1));
    waitUntil(() -> zeebeState.getBlackListState().isOnBlacklist(mockTypedRecord));
  }

  @Test
  public void shouldNotBlacklistInstanceOnCommand() {
    // given
    when(commandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);
    final List<Long> processedInstances = new ArrayList<>();
    final TypedRecordProcessor<JobRecord> dumpProcessor =
        spy(
            new TypedRecordProcessor<>() {
              @Override
              public void processRecord(
                  final TypedRecord<JobRecord> record,
                  final TypedResponseWriter responseWriter,
                  final TypedStreamWriter streamWriter) {
                processedInstances.add(record.getValue().getProcessInstanceKey());
                streamWriter.appendFollowUpEvent(
                    record.getKey(),
                    ProcessInstanceIntent.ELEMENT_COMPLETED,
                    Records.processInstance(2));
              }
            });
    final TypedRecordProcessor<JobRecord> errorProneProcessor =
        new TypedRecordProcessor<>() {
          @Override
          public void processRecord(
              final TypedRecord<JobRecord> record,
              final TypedResponseWriter responseWriter,
              final TypedStreamWriter streamWriter) {
            throw new RuntimeException("expected");
          }
        };

    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .onCommand(ValueType.JOB, JobIntent.COMPLETE, errorProneProcessor)
              .onEvent(ValueType.JOB, JobIntent.ACTIVATED, dumpProcessor);
        });

    streams
        .newRecord(STREAM_NAME)
        .event(Records.job(1))
        .recordType(RecordType.COMMAND)
        .intent(JobIntent.COMPLETE)
        .key(keyGenerator.nextKey())
        .write();
    streams
        .newRecord(STREAM_NAME)
        .event(Records.job(1))
        .recordType(RecordType.EVENT)
        .intent(JobIntent.ACTIVATED)
        .key(keyGenerator.nextKey())
        .write();

    // other instance
    streams
        .newRecord(STREAM_NAME)
        .event(Records.job(2))
        .recordType(RecordType.EVENT)
        .intent(JobIntent.ACTIVATED)
        .key(keyGenerator.nextKey())
        .write();

    // when
    waitForRecordWhichSatisfies(
        e ->
            Records.isEvent(
                e, ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETED));

    // then
    final RecordMetadata metadata = new RecordMetadata();
    metadata.valueType(ValueType.PROCESS_INSTANCE);
    final MockTypedRecord<ProcessInstanceRecord> mockTypedRecord =
        new MockTypedRecord<>(0, metadata, Records.processInstance(1));
    Assertions.assertThat(zeebeState.getBlackListState().isOnBlacklist(mockTypedRecord)).isFalse();

    verify(dumpProcessor, timeout(1000).times(2)).processRecord(any(), any(), any(), any());
    assertThat(processedInstances).containsExactly(1L, 2L);
  }

  @Test
  public void shouldNotBlacklistInstanceAndIgnoreTimerStartEvents() {
    // given
    when(commandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);
    final List<Long> processedInstances = new ArrayList<>();
    final TypedRecordProcessor<DeploymentRecord> errorProneProcessor =
        new TypedRecordProcessor<>() {
          @Override
          public void processRecord(
              final TypedRecord<DeploymentRecord> record,
              final TypedResponseWriter responseWriter,
              final TypedStreamWriter streamWriter) {
            if (record.getKey() == 0) {
              throw new RuntimeException("expected");
            }
            processedInstances.add(TimerInstance.NO_ELEMENT_INSTANCE);
            streamWriter.appendFollowUpEvent(
                record.getKey(),
                TimerIntent.CREATED,
                Records.timer(TimerInstance.NO_ELEMENT_INSTANCE));
          }
        };
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .timerWithDuration("PT1S")
            .endEvent()
            .done();
    final DeploymentRecord firstDeploymentRecord = new DeploymentRecord();
    firstDeploymentRecord
        .resources()
        .add()
        .setResourceName("process.bpmn")
        .setResource(Bpmn.convertToString(process).getBytes());
    final DeploymentRecord secondDeploymentRecord = new DeploymentRecord();
    secondDeploymentRecord
        .resources()
        .add()
        .setResourceName("process2.bpmn")
        .setResource(Bpmn.convertToString(process).getBytes());

    streams.startStreamProcessor(
        STREAM_NAME,
        DefaultZeebeDbFactory.defaultFactory(),
        (processingContext) -> {
          zeebeState = processingContext.getZeebeState();
          return TypedRecordProcessors.processors(
                  zeebeState.getKeyGenerator(), processingContext.getWriters())
              .onCommand(ValueType.DEPLOYMENT, DeploymentIntent.CREATE, errorProneProcessor);
        });

    streams
        .newRecord(STREAM_NAME)
        .event(firstDeploymentRecord)
        .recordType(RecordType.COMMAND)
        .intent(DeploymentIntent.CREATE)
        .key(0)
        .write();
    streams
        .newRecord(STREAM_NAME)
        .event(secondDeploymentRecord)
        .recordType(RecordType.COMMAND)
        .intent(DeploymentIntent.CREATE)
        .key(1)
        .write();

    // when
    waitForRecordWhichSatisfies(e -> Records.isEvent(e, ValueType.TIMER, TimerIntent.CREATED));

    // then
    final RecordMetadata metadata = new RecordMetadata();
    metadata.valueType(ValueType.TIMER);
    final MockTypedRecord<TimerRecord> mockTypedRecord =
        new MockTypedRecord<>(0, metadata, Records.timer(TimerInstance.NO_ELEMENT_INSTANCE));
    Assertions.assertThat(zeebeState.getBlackListState().isOnBlacklist(mockTypedRecord)).isFalse();
    assertThat(processedInstances).containsExactly(TimerInstance.NO_ELEMENT_INSTANCE);
  }

  private void waitForRecordWhichSatisfies(final Predicate<LoggedEvent> filter) {
    TestUtil.doRepeatedly(() -> streams.events(STREAM_NAME).filter(filter).findFirst())
        .until(o -> o.isPresent())
        .get();
  }

  protected static class ErrorProneProcessor
      implements TypedRecordProcessor<ProcessInstanceRecord> {

    public final AtomicLong processCount = new AtomicLong(0);

    @Override
    public void processRecord(
        final TypedRecord<ProcessInstanceRecord> record,
        final TypedResponseWriter responseWriter,
        final TypedStreamWriter streamWriter) {
      processCount.incrementAndGet();
      throw new RuntimeException("expected");
    }

    public long getProcessCount() {
      return processCount.get();
    }
  }

  protected static class DumpProcessor implements TypedRecordProcessor<ProcessInstanceRecord> {
    final List<Long> processedInstances = new ArrayList<>();

    @Override
    public void processRecord(
        final TypedRecord<ProcessInstanceRecord> record,
        final TypedResponseWriter responseWriter,
        final TypedStreamWriter streamWriter) {
      processedInstances.add(record.getValue().getProcessInstanceKey());
      streamWriter.appendFollowUpEvent(
          record.getKey(), ProcessInstanceIntent.ELEMENT_COMPLETED, record.getValue());
    }
  }
}
