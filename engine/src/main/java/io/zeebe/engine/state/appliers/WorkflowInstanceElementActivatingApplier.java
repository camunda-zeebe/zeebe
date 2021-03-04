/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.instance.StoredRecord.Purpose;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableVariableState;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;

/** Applies state changes for `WorkflowInstance:Element_Activating` */
final class WorkflowInstanceElementActivatingApplier
    implements TypedEventApplier<WorkflowInstanceIntent, WorkflowInstanceRecord> {

  private final MutableElementInstanceState elementInstanceState;
  private final MutableVariableState variableState;

  public WorkflowInstanceElementActivatingApplier(
      final MutableElementInstanceState elementInstanceState,
      final MutableVariableState variableState) {
    this.elementInstanceState = elementInstanceState;
    this.variableState = variableState;
  }

  @Override
  public void applyState(final long elementInstanceKey, final WorkflowInstanceRecord value) {
    final var flowScopeInstance = elementInstanceState.getInstance(value.getFlowScopeKey());
    elementInstanceState.newInstance(
        flowScopeInstance, elementInstanceKey, value, WorkflowInstanceIntent.ELEMENT_ACTIVATING);

    final var flowScopeElementType = flowScopeInstance.getValue().getBpmnElementType();
    final var currentElementType = value.getBpmnElementType();
    if ((currentElementType == BpmnElementType.START_EVENT
            && (flowScopeElementType == BpmnElementType.SUB_PROCESS
                || flowScopeElementType == BpmnElementType.PROCESS))
        || flowScopeElementType == BpmnElementType.MULTI_INSTANCE_BODY) {
      // we currently spawn new tokens only for container elements
      // we might spawn tokens for other bpmn element types as well later, then we can improve here
      elementInstanceState.spawnToken(flowScopeInstance.getKey());
    }

    if (currentElementType == BpmnElementType.START_EVENT
        && flowScopeElementType == BpmnElementType.SUB_PROCESS) {
      // the event variables are stored as temporary variables in the scope of the
      // subprocess
      // - move them to the scope of the start event to apply the output variable mappings
      final var variables = variableState.getTemporaryVariables(flowScopeInstance.getKey());

      if (variables != null) {
        variableState.setTemporaryVariables(elementInstanceKey, variables);
        variableState.removeTemporaryVariables(flowScopeInstance.getKey());
      }
    }

    // We store the record to use it on resolving the incident, which is no longer used after
    // migrating the incident processor.
    // In order to migrate the other processors we need to write the record in an event applier. The
    // record is removed in the ACTIVATED again
    // (which happens either after resolving or immediately)
    // todo: we need to remove it later
    elementInstanceState.storeRecord(
        elementInstanceKey,
        value.getFlowScopeKey(),
        value,
        WorkflowInstanceIntent.ACTIVATE_ELEMENT,
        Purpose.FAILED);
  }
}
