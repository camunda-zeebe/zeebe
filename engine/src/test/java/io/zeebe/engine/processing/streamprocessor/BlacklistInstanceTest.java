/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.streamprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.util.ZeebeStateRule;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.JobBatchIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.MessageIntent;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.protocol.record.intent.MessageSubscriptionIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceCreationIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.intent.VariableDocumentIntent;
import io.zeebe.protocol.record.intent.VariableIntent;
import io.zeebe.protocol.record.value.ProcessInstanceRelated;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class BlacklistInstanceTest {

  @ClassRule public static final ZeebeStateRule ZEEBE_STATE_RULE = new ZeebeStateRule();
  private static final AtomicLong KEY_GENERATOR = new AtomicLong(0);

  @Parameter(0)
  public ValueType recordValueType;

  @Parameter(1)
  public Intent recordIntent;

  @Parameter(2)
  public boolean expectedToBlacklist;

  private long processInstanceKey;

  @Parameters(name = "{0} {1} should blacklist instance {2}")
  public static Object[][] parameters() {
    return new Object[][] {
      ////////////////////////////////////////
      ////////////// DEPLOYMENTS /////////////
      ////////////////////////////////////////
      {ValueType.DEPLOYMENT, DeploymentIntent.CREATE, false},
      {ValueType.DEPLOYMENT, DeploymentIntent.CREATED, false},
      {ValueType.DEPLOYMENT, DeploymentIntent.DISTRIBUTE, false},
      {ValueType.DEPLOYMENT, DeploymentIntent.DISTRIBUTED, false},

      ////////////////////////////////////////
      ////////////// INCIDENTS ///////////////
      ////////////////////////////////////////
      {ValueType.INCIDENT, IncidentIntent.CREATE, true},
      {ValueType.INCIDENT, IncidentIntent.CREATED, true},
      {ValueType.INCIDENT, IncidentIntent.RESOLVED, true},

      // USER COMMAND
      {ValueType.INCIDENT, IncidentIntent.RESOLVE, false},

      ////////////////////////////////////////
      ////////////// JOB BATCH ///////////////
      ////////////////////////////////////////
      {ValueType.JOB_BATCH, JobBatchIntent.ACTIVATE, false},
      {ValueType.JOB_BATCH, JobBatchIntent.ACTIVATED, false},

      ////////////////////////////////////////
      //////////////// JOBS //////////////////
      ////////////////////////////////////////
      {ValueType.JOB, JobIntent.CREATE, true},
      {ValueType.JOB, JobIntent.CREATED, true},
      {ValueType.JOB, JobIntent.ACTIVATED, true},
      {ValueType.JOB, JobIntent.COMPLETED, true},
      {ValueType.JOB, JobIntent.TIME_OUT, true},
      {ValueType.JOB, JobIntent.TIMED_OUT, true},
      {ValueType.JOB, JobIntent.FAILED, true},
      {ValueType.JOB, JobIntent.RETRIES_UPDATED, true},
      {ValueType.JOB, JobIntent.CANCEL, true},
      {ValueType.JOB, JobIntent.CANCELED, true},

      // USER COMMAND
      {ValueType.JOB, JobIntent.COMPLETE, false},
      {ValueType.JOB, JobIntent.FAIL, false},
      {ValueType.JOB, JobIntent.UPDATE_RETRIES, false},

      ////////////////////////////////////////
      ////////////// MESSAGES ////////////////
      ////////////////////////////////////////
      {ValueType.MESSAGE, MessageIntent.PUBLISH, false},
      {ValueType.MESSAGE, MessageIntent.PUBLISHED, false},
      {ValueType.MESSAGE, MessageIntent.EXPIRE, false},
      {ValueType.MESSAGE, MessageIntent.EXPIRED, false},

      ////////////////////////////////////////
      ////////// MSG START EVENT SUB /////////
      ////////////////////////////////////////
      {
        ValueType.MESSAGE_START_EVENT_SUBSCRIPTION,
        MessageStartEventSubscriptionIntent.CREATED,
        false
      },
      {
        ValueType.MESSAGE_START_EVENT_SUBSCRIPTION,
        MessageStartEventSubscriptionIntent.DELETED,
        false
      },

      ////////////////////////////////////////
      /////////////// MSG SUB ////////////////
      ////////////////////////////////////////
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.CREATE, true},
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.CREATED, true},
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.CORRELATE, true},
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.CORRELATED, true},
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.DELETE, true},
      {ValueType.MESSAGE_SUBSCRIPTION, MessageSubscriptionIntent.DELETED, true},

      ////////////////////////////////////////
      //////////////// TIMERS ////////////////
      ////////////////////////////////////////
      {ValueType.TIMER, TimerIntent.CREATE, true},
      {ValueType.TIMER, TimerIntent.CREATED, true},
      {ValueType.TIMER, TimerIntent.TRIGGER, true},
      {ValueType.TIMER, TimerIntent.TRIGGERED, true},
      {ValueType.TIMER, TimerIntent.CANCEL, true},
      {ValueType.TIMER, TimerIntent.CANCELED, true},

      ////////////////////////////////////////
      /////////////// VAR DOC ////////////////
      ////////////////////////////////////////
      {ValueType.VARIABLE_DOCUMENT, VariableDocumentIntent.UPDATE, false},
      {ValueType.VARIABLE_DOCUMENT, VariableDocumentIntent.UPDATED, false},

      ////////////////////////////////////////
      /////////////// VARIABLE ///////////////
      ////////////////////////////////////////
      {ValueType.VARIABLE, VariableIntent.CREATED, false},
      {ValueType.VARIABLE, VariableIntent.UPDATED, false},

      ////////////////////////////////////////
      ////////// PROCESS INSTANCE ///////////
      ////////////////////////////////////////
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATING, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_ACTIVATED, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETING, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_COMPLETED, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATING, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.ELEMENT_TERMINATED, true},
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.EVENT_OCCURRED, true},

      // USER COMMAND
      {ValueType.PROCESS_INSTANCE, ProcessInstanceIntent.CANCEL, false},

      ////////////////////////////////////////
      //////// PROCESS INSTANCE CRE /////////
      ////////////////////////////////////////
      {ValueType.PROCESS_INSTANCE_CREATION, ProcessInstanceCreationIntent.CREATE, false},
      {ValueType.PROCESS_INSTANCE_CREATION, ProcessInstanceCreationIntent.CREATED, true},

      ////////////////////////////////////////
      //////// PROCESS INSTANCE SUB /////////
      ////////////////////////////////////////
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.OPEN, true},
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.OPENED, true},
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.CORRELATE, true},
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.CORRELATED, true},
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.CLOSE, true},
      {ValueType.PROCESS_INSTANCE_SUBSCRIPTION, ProcessInstanceSubscriptionIntent.CLOSED, true}
    };
  }

  @Before
  public void setup() {
    initMocks(this);
    processInstanceKey = KEY_GENERATOR.getAndIncrement();
  }

  @Test
  public void shouldBlacklist() {
    // given
    final RecordMetadata metadata = new RecordMetadata();
    metadata.intent(recordIntent);
    metadata.valueType(recordValueType);
    final TypedEventImpl typedEvent = new TypedEventImpl(1);
    final LoggedEvent loggedEvent = mock(LoggedEvent.class);
    when(loggedEvent.getPosition()).thenReturn(1024L);

    typedEvent.wrap(loggedEvent, metadata, new Value());

    // when
    final ZeebeState zeebeState = ZEEBE_STATE_RULE.getZeebeState();
    zeebeState.getBlackListState().tryToBlacklist(typedEvent, (processInstanceKey) -> {});

    // then
    metadata.intent(ProcessInstanceIntent.ELEMENT_ACTIVATING);
    metadata.valueType(ValueType.PROCESS_INSTANCE);
    typedEvent.wrap(null, metadata, new Value());
    assertThat(zeebeState.getBlackListState().isOnBlacklist(typedEvent))
        .isEqualTo(expectedToBlacklist);
  }

  private final class Value extends UnifiedRecordValue implements ProcessInstanceRelated {

    @Override
    public long getProcessInstanceKey() {
      return processInstanceKey;
    }
  }
}
