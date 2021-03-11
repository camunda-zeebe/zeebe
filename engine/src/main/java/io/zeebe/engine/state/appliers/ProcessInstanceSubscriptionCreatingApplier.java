/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.message.ProcessInstanceSubscription;
import io.zeebe.engine.state.mutable.MutableProcessInstanceSubscriptionState;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import io.zeebe.util.sched.clock.ActorClock;

public final class ProcessInstanceSubscriptionCreatingApplier
    implements TypedEventApplier<
        ProcessInstanceSubscriptionIntent, ProcessInstanceSubscriptionRecord> {

  private final MutableProcessInstanceSubscriptionState subscriptionState;

  public ProcessInstanceSubscriptionCreatingApplier(
      final MutableProcessInstanceSubscriptionState subscriptionState) {
    this.subscriptionState = subscriptionState;
  }

  @Override
  public void applyState(final long key, final ProcessInstanceSubscriptionRecord value) {
    // TODO (saig0): reuse the subscription record in the state (#6533)
    final var subscription = new ProcessInstanceSubscription();

    subscription.setSubscriptionPartitionId(value.getSubscriptionPartitionId());
    subscription.setMessageName(value.getMessageNameBuffer());
    subscription.setElementInstanceKey(value.getElementInstanceKey());
    subscription.setProcessInstanceKey(value.getProcessInstanceKey());
    subscription.setBpmnProcessId(value.getBpmnProcessIdBuffer());
    subscription.setCorrelationKey(value.getCorrelationKeyBuffer());
    subscription.setTargetElementId(value.getElementIdBuffer());
    subscription.setCloseOnCorrelate(value.isInterrupting());

    // TODO (saig0): the send time for the retry should be deterministic (#6364)
    final var sentTime = ActorClock.currentTimeMillis();
    subscription.setCommandSentTime(sentTime);

    if (subscriptionState.existSubscriptionForElementInstance(
        value.getElementInstanceKey(), value.getMessageNameBuffer())) {
      // TODO (saig0): avoid state change on reprocessing of a not yet migrated processor (#6200)
      return;
    }

    subscriptionState.put(subscription);
  }
}
