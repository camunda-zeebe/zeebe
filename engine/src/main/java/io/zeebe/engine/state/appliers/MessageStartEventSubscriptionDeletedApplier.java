/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableMessageStartEventSubscriptionState;
import io.zeebe.protocol.impl.record.value.message.MessageStartEventSubscriptionRecord;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;

public final class MessageStartEventSubscriptionDeletedApplier
    implements TypedEventApplier<
        MessageStartEventSubscriptionIntent, MessageStartEventSubscriptionRecord> {

  private final MutableMessageStartEventSubscriptionState subscriptionState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;

  public MessageStartEventSubscriptionDeletedApplier(
      final MutableMessageStartEventSubscriptionState subscriptionState,
      final MutableEventScopeInstanceState eventScopeInstanceState) {
    this.subscriptionState = subscriptionState;
    this.eventScopeInstanceState = eventScopeInstanceState;
  }

  @Override
  public void applyState(final long key, final MessageStartEventSubscriptionRecord value) {
    final var processDefinitionKey = value.getProcessDefinitionKey();

    subscriptionState.removeSubscriptionsOfProcess(processDefinitionKey);

    eventScopeInstanceState.deleteInstance(processDefinitionKey);
  }
}
