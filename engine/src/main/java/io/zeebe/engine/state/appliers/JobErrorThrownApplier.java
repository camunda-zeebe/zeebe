/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.processing.job.JobThrowErrorProcessor;
import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.analyzers.CatchEventAnalyzer;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableJobState;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.record.intent.JobIntent;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class JobErrorThrownApplier implements TypedEventApplier<JobIntent, JobRecord> {

  private static final DirectBuffer NO_VARIABLES = new UnsafeBuffer();

  private final MutableJobState jobState;
  private final MutableElementInstanceState elementInstanceState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;
  private final CatchEventAnalyzer stateAnalyzer;

  JobErrorThrownApplier(final ZeebeState state) {
    jobState = state.getJobState();
    elementInstanceState = state.getElementInstanceState();
    eventScopeInstanceState = state.getEventScopeInstanceState();

    stateAnalyzer =
        new CatchEventAnalyzer(state.getWorkflowState(), state.getElementInstanceState());
  }

  @Override
  public void applyState(final long jobKey, final JobRecord job) {
    final var serviceTaskInstanceId = job.getElementId();
    jobState.throwError(jobKey, job);

    if (!JobThrowErrorProcessor.NO_CATCH_EVENT_FOUND.equals(serviceTaskInstanceId)) {
      final var serviceTaskInstance = elementInstanceState.getInstance(job.getElementInstanceKey());

      removeJobReference(jobKey, job, serviceTaskInstance);

      // TODO (#6472) remove this after the right event is being written
      triggerEvent(jobKey, job, serviceTaskInstance);
    }
  }

  private void removeJobReference(
      final long jobKey, final JobRecord job, final ElementInstance serviceTaskInstance) {
    // remove job reference to not cancel it while terminating the task
    serviceTaskInstance.setJobKey(-1L);
    elementInstanceState.updateInstance(serviceTaskInstance);

    jobState.delete(jobKey, job);
  }

  private void triggerEvent(
      final long jobKey, final JobRecord job, final ElementInstance serviceTaskInstance) {
    final var foundCatchEvent =
        stateAnalyzer.findCatchEvent(job.getErrorCodeBuffer(), serviceTaskInstance);

    final var eventScopeInstance = foundCatchEvent.getElementInstance();
    final var catchEvent = foundCatchEvent.getCatchEvent();

    eventScopeInstanceState.triggerEvent(
        eventScopeInstance.getKey(), jobKey, catchEvent.getId(), NO_VARIABLES);
  }
}
