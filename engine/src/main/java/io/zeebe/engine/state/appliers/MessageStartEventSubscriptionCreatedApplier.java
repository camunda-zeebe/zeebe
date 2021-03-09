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
import java.util.Collections;
import java.util.List;
import org.agrona.DirectBuffer;

public final class MessageStartEventSubscriptionCreatedApplier
    implements TypedEventApplier<
        MessageStartEventSubscriptionIntent, MessageStartEventSubscriptionRecord> {

  private static final List<DirectBuffer> NO_INTERRUPTING_ELEMENT_IDS = Collections.emptyList();

  private final MutableMessageStartEventSubscriptionState subscriptionState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;

  public MessageStartEventSubscriptionCreatedApplier(
      final MutableMessageStartEventSubscriptionState subscriptionState,
      final MutableEventScopeInstanceState eventScopeInstanceState) {
    this.subscriptionState = subscriptionState;
    this.eventScopeInstanceState = eventScopeInstanceState;
  }

  @Override
  public void applyState(final long key, final MessageStartEventSubscriptionRecord value) {

    subscriptionState.put(value);

    eventScopeInstanceState.createIfNotExists(
        value.getProcessDefinitionKey(), NO_INTERRUPTING_ELEMENT_IDS);
  }
}
