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

public enum ProcessInstanceSubscriptionIntent implements ProcessInstanceRelatedIntent {
  CREATING((short) 0),
  CREATE((short) 1),
  CREATED((short) 2),

  CORRELATE((short) 3),
  CORRELATED((short) 4),

  CLOSE((short) 5),
  CLOSED((short) 6);

  private final short value;
  private final boolean shouldBlacklist;

  ProcessInstanceSubscriptionIntent(final short value) {
    this(value, true);
  }

  ProcessInstanceSubscriptionIntent(final short value, final boolean shouldBlacklist) {
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
        return CREATING;
      case 1:
        return CREATE;
      case 2:
        return CREATED;
      case 3:
        return CORRELATE;
      case 4:
        return CORRELATED;
      case 5:
        return CLOSE;
      case 6:
        return CLOSED;
      default:
        return Intent.UNKNOWN;
    }
  }

  @Override
  public boolean shouldBlacklistInstanceOnError() {
    return shouldBlacklist;
  }
}
