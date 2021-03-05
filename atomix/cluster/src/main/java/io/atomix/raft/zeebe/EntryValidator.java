/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
package io.atomix.raft.zeebe;

import io.atomix.raft.storage.log.entry.ApplicationEntry;

@FunctionalInterface
public interface EntryValidator {

  /**
   * Validates the current entry, which should be append to the log, and compares it with the last
   * appended entry to ensure consistency. The ValidationResult reflects the outcome of the
   * validation and holds an error message when the validation fails.
   *
   * @param lastEntry the previous zeebe entry
   * @param entry the zeebe entry to be appended
   * @return a ValidationResult containing the validation result and an error message, if it failed
   */
  ValidationResult validateEntry(ApplicationEntry lastEntry, ApplicationEntry entry);
}
