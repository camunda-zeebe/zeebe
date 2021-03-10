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
package io.atomix.raft.storage.log;

import io.atomix.cluster.MemberId;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.ApplicationEntryDecoder;
import io.atomix.raft.storage.log.entry.ApplicationEntryEncoder;
import io.atomix.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.raft.storage.log.entry.ConfigurationEntryDecoder;
import io.atomix.raft.storage.log.entry.ConfigurationEntryDecoder.RaftMemberDecoder;
import io.atomix.raft.storage.log.entry.ConfigurationEntryEncoder;
import io.atomix.raft.storage.log.entry.InitialEntryDecoder;
import io.atomix.raft.storage.log.entry.InitialEntryEncoder;
import io.atomix.raft.storage.log.entry.InitializeEntry;
import io.atomix.raft.storage.log.entry.MessageHeaderDecoder;
import io.atomix.raft.storage.log.entry.MessageHeaderEncoder;
import io.atomix.raft.storage.log.entry.RaftEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntryDecoder;
import io.atomix.raft.storage.log.entry.RaftLogEntryEncoder;
import io.atomix.raft.storage.log.entry.Type;
import java.time.Instant;
import java.util.ArrayList;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class RaftEntrySBESerializer implements RaftEntrySerializer {
  final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  final RaftLogEntryEncoder raftLogEntryEncoder = new RaftLogEntryEncoder();
  final ApplicationEntryEncoder applicationEntryEncoder = new ApplicationEntryEncoder();
  final ConfigurationEntryEncoder configurationEntryEncoder = new ConfigurationEntryEncoder();
  final InitialEntryEncoder initialEntryEncoder = new InitialEntryEncoder();

  final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  final RaftLogEntryDecoder raftLogEntryDecoder = new RaftLogEntryDecoder();
  final ApplicationEntryDecoder applicationEntryDecoder = new ApplicationEntryDecoder();
  final ConfigurationEntryDecoder configurationEntryDecoder = new ConfigurationEntryDecoder();
  final InitialEntryDecoder initialEntryDecoder = new InitialEntryDecoder();

  @Override
  public int writeApplicationEntry(
      final long term,
      final ApplicationEntry entry,
      final MutableDirectBuffer buffer,
      final int offset) {

    headerEncoder
        .wrap(buffer, offset)
        .blockLength(raftLogEntryEncoder.sbeBlockLength())
        .templateId(raftLogEntryEncoder.sbeTemplateId())
        .schemaId(raftLogEntryEncoder.sbeSchemaId())
        .version(raftLogEntryEncoder.sbeSchemaVersion());
    raftLogEntryEncoder.wrap(buffer, offset + headerEncoder.encodedLength());
    raftLogEntryEncoder.term(term);

    final var entryOffset = headerEncoder.encodedLength() + raftLogEntryEncoder.encodedLength();

    headerEncoder
        .wrap(buffer, offset + entryOffset)
        .blockLength(applicationEntryEncoder.sbeBlockLength())
        .templateId(applicationEntryEncoder.sbeTemplateId())
        .schemaId(applicationEntryEncoder.sbeSchemaId())
        .version(applicationEntryEncoder.sbeSchemaVersion());
    applicationEntryEncoder.wrap(buffer, offset + entryOffset + headerEncoder.encodedLength());
    applicationEntryEncoder
        .lowestAsqn(entry.lowestPosition())
        .highestAsqn(entry.highestPosition())
        .putApplicationData(new UnsafeBuffer(entry.data()), 0, entry.data().capacity());

    return entryOffset + headerEncoder.encodedLength() + applicationEntryEncoder.encodedLength();
  }

  @Override
  public int writeInitialEntry(
      final long term,
      final InitializeEntry entry,
      final MutableDirectBuffer buffer,
      final int offset) {

    headerEncoder
        .wrap(buffer, offset)
        .blockLength(raftLogEntryEncoder.sbeBlockLength())
        .templateId(raftLogEntryEncoder.sbeTemplateId())
        .schemaId(raftLogEntryEncoder.sbeSchemaId())
        .version(raftLogEntryEncoder.sbeSchemaVersion());
    raftLogEntryEncoder.wrap(buffer, offset + headerEncoder.encodedLength());
    raftLogEntryEncoder.term(term);

    final var entryOffset = headerEncoder.encodedLength() + raftLogEntryEncoder.encodedLength();

    headerEncoder
        .wrap(buffer, offset + entryOffset)
        .blockLength(initialEntryEncoder.sbeBlockLength())
        .templateId(initialEntryEncoder.sbeTemplateId())
        .schemaId(initialEntryEncoder.sbeSchemaId())
        .version(initialEntryEncoder.sbeSchemaVersion());
    initialEntryEncoder.wrap(buffer, offset + entryOffset + headerEncoder.encodedLength());

    return entryOffset + headerEncoder.encodedLength() + initialEntryEncoder.encodedLength();
  }

  @Override
  public int writeConfigurationEntry(
      final long term,
      final ConfigurationEntry entry,
      final MutableDirectBuffer buffer,
      final int offset) {
    headerEncoder
        .wrap(buffer, offset)
        .blockLength(raftLogEntryEncoder.sbeBlockLength())
        .templateId(raftLogEntryEncoder.sbeTemplateId())
        .schemaId(raftLogEntryEncoder.sbeSchemaId())
        .version(raftLogEntryEncoder.sbeSchemaVersion());
    raftLogEntryEncoder.wrap(buffer, offset + headerEncoder.encodedLength());
    raftLogEntryEncoder.term(term);

    final var entryOffset = headerEncoder.encodedLength() + raftLogEntryEncoder.encodedLength();

    headerEncoder
        .wrap(buffer, offset + entryOffset)
        .blockLength(configurationEntryEncoder.sbeBlockLength())
        .templateId(configurationEntryEncoder.sbeTemplateId())
        .schemaId(configurationEntryEncoder.sbeSchemaId())
        .version(configurationEntryEncoder.sbeSchemaVersion());

    configurationEntryEncoder.wrap(buffer, offset + entryOffset + headerEncoder.encodedLength());

    configurationEntryEncoder.timestamp(entry.timestamp());
    final var raftMemberEncoder = configurationEntryEncoder.raftMemberCount(entry.members().size());
    for (final RaftMember member : entry.members()) {
      final int memberId =
          Integer.parseInt(
              member.memberId().id()); // TODO: Assumption memberId is always an integer
      raftMemberEncoder
          .next()
          .type(getSBEType(member.getType()))
          .updated(member.getLastUpdated().toEpochMilli())
          .memberId(memberId);
    }

    return entryOffset + headerEncoder.encodedLength() + configurationEntryEncoder.encodedLength();
  }

  private ConfigurationEntry readConfigurationEntry(
      final DirectBuffer buffer, final int entryOffset) {

    configurationEntryDecoder.wrap(
        buffer,
        entryOffset + headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    final long timestamp = configurationEntryDecoder.timestamp();

    final RaftMemberDecoder memberDecoder1 = configurationEntryDecoder.raftMember();
    final ArrayList<RaftMember> members =
        new ArrayList<>(memberDecoder1.count());
    for (final RaftMemberDecoder memberDecoder : memberDecoder1) {
      final RaftMember.Type type = getRaftMemberType(memberDecoder.type());
      final Instant updated = Instant.ofEpochMilli(memberDecoder.updated());
      final String memberId = String.valueOf(memberDecoder.memberId());
      members.add(new DefaultRaftMember(MemberId.from(memberId), type, updated));
    }

    return new ConfigurationEntry(timestamp, members);
  }

  @Override
  public RaftLogEntry getRaftLogEntry(final DirectBuffer buffer) {
    headerDecoder.wrap(buffer, 0);
    raftLogEntryDecoder.wrap(
        buffer,
        headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());
    final long term = raftLogEntryDecoder.term();

    final int entryOffset = headerDecoder.encodedLength() + raftLogEntryDecoder.encodedLength();
    headerDecoder.wrap(buffer, entryOffset);

    final RaftEntry entry;

    if (headerDecoder.schemaId() == applicationEntryDecoder.sbeSchemaId()
        && headerDecoder.templateId() == applicationEntryDecoder.sbeTemplateId()) {
      entry = readApplicationEntry(buffer, entryOffset);
    } else if (headerDecoder.schemaId() == configurationEntryDecoder.sbeSchemaId()
        && headerDecoder.templateId() == configurationEntryDecoder.sbeTemplateId()) {
      entry = readConfigurationEntry(buffer, entryOffset);
    } else if (headerDecoder.schemaId() == initialEntryDecoder.sbeSchemaId()
        && headerDecoder.templateId() == initialEntryDecoder.sbeTemplateId()) {
      entry = readInitialEntry(buffer, entryOffset);
    } else {
      // TODO
      throw new IllegalStateException();
    }

    return new RaftLogEntry(term, entry);
  }

  private Type getSBEType(final RaftMember.Type type) {
    switch (type) {
      case ACTIVE:
        return Type.ACTIVE;
      case PASSIVE:
        return Type.PASSIVE;

      case INACTIVE:
        return Type.INACTIVE;
      case PROMOTABLE:
        return Type.PROMOTABLE;
      default:
        throw new IllegalStateException();
    }
  }

  private ApplicationEntry readApplicationEntry(final DirectBuffer buffer, final int entryOffset) {
    applicationEntryDecoder.wrap(
        buffer,
        entryOffset + headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    final DirectBuffer data = new UnsafeBuffer();
    applicationEntryDecoder.wrapApplicationData(data);

    return new ApplicationEntry(
        applicationEntryDecoder.lowestAsqn(), applicationEntryDecoder.highestAsqn(), data);
  }



  private RaftMember.Type getRaftMemberType(final Type type) {
    switch (type) {
      case ACTIVE:
        return RaftMember.Type.ACTIVE;
      case PASSIVE:
        return RaftMember.Type.PASSIVE;

      case INACTIVE:
        return RaftMember.Type.INACTIVE;
      case PROMOTABLE:
        return RaftMember.Type.PROMOTABLE;
      default:
        throw new IllegalStateException();
    }
  }

  private InitializeEntry readInitialEntry(final DirectBuffer buffer, final int entryOffset) {
    initialEntryDecoder.wrap(
        buffer,
        entryOffset + headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    return new InitializeEntry();
  }
}
