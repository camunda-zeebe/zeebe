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
package io.zeebe.journal.file;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public final class ChecksumGenerator {

  private final CRC32 crc32 = new CRC32();

  /** Compute checksum of given ByteBuffer */
  public long compute(final ByteBuffer buffer, final int offset, final int length) {
    final var slice = buffer.position(offset).slice();
    slice.limit(length);
    crc32.reset();
    crc32.update(slice);
    return crc32.getValue();
  }
}
