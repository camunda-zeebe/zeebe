/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message;

import static io.zeebe.util.buffer.BufferUtil.cloneBuffer;

import io.zeebe.protocol.impl.record.value.message.MessageStartEventSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.message.MessageSubscriptionRecord;
import io.zeebe.util.collection.Reusable;
import io.zeebe.util.collection.ReusableObjectList;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class Subscriptions {

  private final ReusableObjectList<Subscription> subscriptions =
      new ReusableObjectList<>(Subscription::new);

  public void clear() {
    subscriptions.clear();
  }

  public boolean contains(final DirectBuffer bpmnProcessId) {
    for (final Subscription subscription : subscriptions) {
      if (subscription.getBpmnProcessId().equals(bpmnProcessId)) {
        return true;
      }
    }
    return false;
  }

  public void add(final MessageSubscriptionRecord subscription) {
    final var newSubscription = subscriptions.add();
    newSubscription.setBpmnProcessId(cloneBuffer(subscription.getBpmnProcessIdBuffer()));
    newSubscription.processInstanceKey = subscription.getProcessInstanceKey();
    newSubscription.elementInstanceKey = subscription.getElementInstanceKey();
  }

  public void add(final MessageStartEventSubscriptionRecord subscription) {
    final var newSubscription = subscriptions.add();
    newSubscription.setBpmnProcessId(cloneBuffer(subscription.getBpmnProcessIdBuffer()));
    newSubscription.isStartEventSubscription = true;
  }

  public void visitBpmnProcessIds(final Consumer<DirectBuffer> bpmnProcessIdConsumer) {
    for (final Subscription subscription : subscriptions) {
      bpmnProcessIdConsumer.accept(subscription.getBpmnProcessId());
    }
  }

  public boolean visitSubscriptions(final SubscriptionVisitor subscriptionConsumer) {
    for (final Subscription subscription : subscriptions) {
      if (!subscription.isStartEventSubscription) {

        final var applied = subscriptionConsumer.apply(subscription);
        if (!applied) {
          return false;
        }
      }
    }
    return true;
  }

  @FunctionalInterface
  public interface SubscriptionVisitor {
    boolean apply(Subscription subscription);
  }

  public final class Subscription implements Reusable {

    private final MutableDirectBuffer bpmnProcessId = new ExpandableArrayBuffer();
    private final DirectBuffer bufferView = new UnsafeBuffer(bpmnProcessId);
    private int bufferLength = 0;

    private long processInstanceKey;
    private long elementInstanceKey;
    private boolean isStartEventSubscription;

    @Override
    public void reset() {
      bufferLength = 0;
      bufferView.wrap(0, 0);

      processInstanceKey = -1L;
      elementInstanceKey = -1L;
      isStartEventSubscription = false;
    }

    public DirectBuffer getBpmnProcessId() {
      return bufferView;
    }

    private void setBpmnProcessId(final DirectBuffer bpmnProcessId) {
      bufferLength = bpmnProcessId.capacity();
      bpmnProcessId.getBytes(0, this.bpmnProcessId, 0, bufferLength);
      bufferView.wrap(this.bpmnProcessId, 0, bufferLength);
    }

    public long getProcessInstanceKey() {
      return processInstanceKey;
    }

    public long getElementInstanceKey() {
      return elementInstanceKey;
    }
  }
}
