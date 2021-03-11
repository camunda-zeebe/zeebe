/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.deployment.model.element;

import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.util.Either;
import java.util.function.BiFunction;

public interface ExecutableCatchEvent extends ExecutableFlowElement {

  boolean isTimer();

  boolean isMessage();

  boolean isError();

  default boolean isNone() {
    return !isTimer() && !isMessage() && !isError();
  }

  ExecutableMessage getMessage();

  default boolean isInterrupting() {
    return true;
  }

  BiFunction<ExpressionProcessor, Long, Either<Failure, Timer>> getTimerFactory();

  ExecutableError getError();
}
