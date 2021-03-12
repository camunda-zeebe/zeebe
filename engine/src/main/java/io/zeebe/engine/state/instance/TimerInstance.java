/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.instance;

import io.zeebe.db.DbValue;
import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.msgpack.property.IntegerProperty;
import io.zeebe.msgpack.property.LongProperty;
import io.zeebe.msgpack.property.StringProperty;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class TimerInstance extends UnpackedObject implements DbValue {

  public static final long NO_ELEMENT_INSTANCE = -1L;

  private final StringProperty handlerNodeIdProp = new StringProperty("handlerNodeId", "");
  private final LongProperty processDefinitionKeyProp =
      new LongProperty("processDefinitionKey", 0L);
  private final LongProperty keyProp = new LongProperty("key", 0L);
  private final LongProperty elementInstanceKeyProp = new LongProperty("elementInstanceKey", 0L);
  private final LongProperty processInstanceKeyProp = new LongProperty("processInstanceKey", 0L);
  private final LongProperty dueDateProp = new LongProperty("dueDate", 0L);
  private final IntegerProperty repetitionsProp = new IntegerProperty("repetitions", 0);

  public TimerInstance() {
    declareProperty(handlerNodeIdProp)
        .declareProperty(processDefinitionKeyProp)
        .declareProperty(keyProp)
        .declareProperty(elementInstanceKeyProp)
        .declareProperty(processInstanceKeyProp)
        .declareProperty(dueDateProp)
        .declareProperty(repetitionsProp);
  }

  public long getElementInstanceKey() {
    return elementInstanceKeyProp.getValue();
  }

  public void setElementInstanceKey(final long elementInstanceKey) {
    elementInstanceKeyProp.setValue(elementInstanceKey);
  }

  public long getDueDate() {
    return dueDateProp.getValue();
  }

  public void setDueDate(final long dueDate) {
    dueDateProp.setValue(dueDate);
  }

  public long getKey() {
    return keyProp.getValue();
  }

  public void setKey(final long key) {
    keyProp.setValue(key);
  }

  public DirectBuffer getHandlerNodeId() {
    return handlerNodeIdProp.getValue();
  }

  public void setHandlerNodeId(final DirectBuffer handlerNodeId) {
    handlerNodeIdProp.setValue(handlerNodeId);
  }

  public int getRepetitions() {
    return repetitionsProp.getValue();
  }

  public void setRepetitions(final int repetitions) {
    repetitionsProp.setValue(repetitions);
  }

  public long getProcessDefinitionKey() {
    return processDefinitionKeyProp.getValue();
  }

  public void setProcessDefinitionKey(final long processDefinitionKey) {
    processDefinitionKeyProp.setValue(processDefinitionKey);
  }

  public long getProcessInstanceKey() {
    return processInstanceKeyProp.getValue();
  }

  public void setProcessInstanceKey(final long processInstanceKey) {
    processInstanceKeyProp.setValue(processInstanceKey);
  }

  @Override
  public void wrap(final DirectBuffer buffer, int offset, final int length) {
    final byte[] bytes = new byte[length];
    final UnsafeBuffer mutableBuffer = new UnsafeBuffer(bytes);
    buffer.getBytes(offset, bytes, 0, length);
    super.wrap(mutableBuffer, 0, length);
  }
}
