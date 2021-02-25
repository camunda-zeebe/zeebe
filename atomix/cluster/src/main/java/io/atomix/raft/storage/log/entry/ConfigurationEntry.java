/*
 * Copyright 2015-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.log.entry;

import static com.google.common.base.MoreObjects.toStringHelper;

import io.atomix.raft.cluster.RaftMember;
import java.util.Collection;
import org.agrona.MutableDirectBuffer;

/**
 * Stores a cluster configuration.
 *
 * <p>The {@code ConfigurationEntry} stores information relevant to a single cluster configuration
 * change. Configuration change entries store a collection of {@link RaftMember members} which each
 * represent a server in the cluster. Each time the set of members changes or a property of a single
 * member changes, a new {@code ConfigurationEntry} must be logged for the configuration change.
 */
public class ConfigurationEntry implements RaftEntry {

  protected final Collection<RaftMember> members;

  public ConfigurationEntry(final Collection<RaftMember> members) {
    this.members = members;
  }

  /**
   * Returns the members.
   *
   * @return The members.
   */
  public Collection<RaftMember> members() {
    return members;
  }

  @Override
  public String toString() {
    return toStringHelper(this).add("members", members).toString();
  }

  @Override
  public void write(final MutableDirectBuffer buffer) {


  }

  @Override
  public int getLength() {
    return 0;
  }
}
