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
package io.atomix.raft.roles;

import io.atomix.raft.RaftServer;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.protocol.AppendRequest;
import io.atomix.raft.protocol.AppendResponse;
import io.atomix.raft.protocol.PollRequest;
import io.atomix.raft.protocol.PollResponse;
import io.atomix.raft.protocol.RaftRequest;
import io.atomix.raft.protocol.RaftResponse;
import io.atomix.raft.protocol.VoteRequest;
import io.atomix.raft.protocol.VoteResponse;
import io.atomix.raft.storage.log.IndexedRaftRecord;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Abstract active state. */
public abstract class ActiveRole extends PassiveRole {

  protected ActiveRole(final RaftContext context) {
    super(context);
  }

  @Override
  public CompletableFuture<AppendResponse> onAppend(final AppendRequest request) {
    raft.checkThread();
    logRequest(request);

    // If the request indicates a term that is greater than the current term then
    // assign that term and leader to the current context and transition to follower.
    final boolean transition = updateTermAndLeader(request.term(), request.leader());

    // Handle the append request.
    final CompletableFuture<AppendResponse> future = handleAppend(request);

    // If a transition is required then transition back to the follower state.
    // If the node is already a follower then the transition will be ignored.
    if (transition) {
      raft.transition(RaftServer.Role.FOLLOWER);
    }
    return future;
  }

  @Override
  public CompletableFuture<PollResponse> onPoll(final PollRequest request) {
    raft.checkThread();
    logRequest(request);
    updateTermAndLeader(request.term(), null);
    return CompletableFuture.completedFuture(logResponse(handlePoll(request)));
  }

  @Override
  public CompletableFuture<VoteResponse> onVote(final VoteRequest request) {
    raft.checkThread();
    logRequest(request);

    // If the request indicates a term that is greater than the current term then
    // assign that term and leader to the current context.
    final boolean transition = updateTermAndLeader(request.term(), null);

    final CompletableFuture<VoteResponse> future =
        CompletableFuture.completedFuture(logResponse(handleVote(request)));
    if (transition) {
      raft.transition(RaftServer.Role.FOLLOWER);
    }
    return future;
  }

  /** Handles a poll request. */
  protected PollResponse handlePoll(final PollRequest request) {
    // If the request term is not as great as the current context term then don't
    // vote for the candidate. We want to vote for candidates that are at least
    // as up to date as us.
    if (request.term() < raft.getTerm()) {
      log.debug("Rejected {}: candidate's term is less than the current term", request);
      return PollResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withAccepted(false)
          .build();
    } else if (isLogUpToDate(request.lastLogIndex(), request.lastLogTerm(), request)) {
      return PollResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withAccepted(true)
          .build();
    } else {
      return PollResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withAccepted(false)
          .build();
    }
  }

  /** Returns a boolean value indicating whether the given candidate's log is up-to-date. */
  boolean isLogUpToDate(final long lastIndex, final long lastTerm, final RaftRequest request) {
    // If the log is empty then vote for the candidate.
    if (raft.getLog().isEmpty()) {
      log.debug("Accepted {}: candidate's log is up-to-date", request);
      return true;
    }

    // Read the last entry from the log.
    final IndexedRaftRecord lastEntry = raft.getLog().getLastEntry();

    // If the candidate's last log term is lower than the local log's last entry term, reject the
    // request.
    if (lastTerm < lastEntry.entry().term()) {
      log.debug(
          "Rejected {}: candidate's last log entry ({}) is at a lower term than the local log ({})",
          request,
          lastTerm,
          lastEntry.entry().term());
      return false;
    }

    // If the candidate's last term is equal to the local log's last entry term, reject the request
    // if the
    // candidate's last index is less than the local log's last index. If the candidate's last log
    // term is
    // greater than the local log's last term then it's considered up to date, and if both have the
    // same term
    // then the candidate's last index must be greater than the local log's last index.
    if (lastTerm == lastEntry.entry().term() && lastIndex < lastEntry.index()) {
      log.debug(
          "Rejected {}: candidate's last log entry ({}) is at a lower index than the local log ({})",
          request,
          lastIndex,
          lastEntry.index());
      return false;
    }

    // If we made it this far, the candidate's last term is greater than or equal to the local log's
    // last
    // term, and if equal to the local log's last term, the candidate's last index is equal to or
    // greater
    // than the local log's last index.
    log.info("Accepted {}: candidate's log is up-to-date", request);
    return true;
  }

  /** Handles a vote request. */
  protected VoteResponse handleVote(final VoteRequest request) {
    // If the request term is not as great as the current context term then don't
    // vote for the candidate. We want to vote for candidates that are at least
    // as up to date as us.
    if (request.term() < raft.getTerm()) {
      log.debug("Rejected {}: candidate's term is less than the current term", request);
      return VoteResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withVoted(false)
          .build();
    }
    // If a leader was already determined for this term then reject the request.
    else if (raft.getLeader() != null) {
      log.debug("Rejected {}: leader already exists", request);
      return VoteResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withVoted(false)
          .build();
    }
    // If the requesting candidate is not a known member of the cluster (to this
    // node) then don't vote for it. Only vote for candidates that we know about.
    else if (!raft.getCluster().getRemoteMemberStates().stream()
        .map(m -> m.getMember().memberId())
        .collect(Collectors.toSet())
        .contains(request.candidate())) {
      log.debug("Rejected {}: candidate is not known to the local member", request);
      return VoteResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withVoted(false)
          .build();
    }
    // If no vote has been cast, check the log and cast a vote if necessary.
    else if (raft.getLastVotedFor() == null) {
      if (isLogUpToDate(request.lastLogIndex(), request.lastLogTerm(), request)) {
        raft.setLastVotedFor(request.candidate());
        return VoteResponse.builder()
            .withStatus(RaftResponse.Status.OK)
            .withTerm(raft.getTerm())
            .withVoted(true)
            .build();
      } else {
        return VoteResponse.builder()
            .withStatus(RaftResponse.Status.OK)
            .withTerm(raft.getTerm())
            .withVoted(false)
            .build();
      }
    }
    // If we already voted for the requesting server, respond successfully.
    else if (raft.getLastVotedFor() == request.candidate()) {
      log.debug(
          "Accepted {}: already voted for {}",
          request,
          raft.getCluster().getMember(raft.getLastVotedFor()).memberId());
      return VoteResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withVoted(true)
          .build();
    }
    // In this case, we've already voted for someone else.
    else {
      log.debug(
          "Rejected {}: already voted for {}",
          request,
          raft.getCluster().getMember(raft.getLastVotedFor()).memberId());
      return VoteResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(raft.getTerm())
          .withVoted(false)
          .build();
    }
  }
}
