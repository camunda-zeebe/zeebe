/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.protocol.record.intent;

public enum TimerIntent implements ProcessInstanceRelatedIntent {
  CREATED((short) 0),

  TRIGGER((short) 1),
  TRIGGERED((short) 2),

  CANCEL((short) 3),
  CANCELED((short) 4);

  private final short value;
  private final boolean shouldBlacklist;

  TimerIntent(final short value) {
    this(value, true);
  }

  TimerIntent(final short value, final boolean shouldBlacklist) {
    this.value = value;
    this.shouldBlacklist = shouldBlacklist;
  }

  @Override
  public short value() {
    return value;
  }

  public static Intent from(final short value) {
    switch (value) {
      case 0:
        return CREATED;
      case 1:
        return TRIGGER;
      case 2:
        return TRIGGERED;
      case 3:
        return CANCEL;
      case 4:
        return CANCELED;
      default:
        return Intent.UNKNOWN;
    }
  }

  @Override
  public boolean shouldBlacklistInstanceOnError() {
    return shouldBlacklist;
  }
}
