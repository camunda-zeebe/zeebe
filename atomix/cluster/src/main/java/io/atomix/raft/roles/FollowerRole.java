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

import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEventListener;
import io.atomix.cluster.MemberId;
import io.atomix.raft.RaftServer;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.cluster.impl.RaftMemberContext;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.protocol.AppendRequest;
import io.atomix.raft.protocol.AppendResponse;
import io.atomix.raft.protocol.ConfigureRequest;
import io.atomix.raft.protocol.ConfigureResponse;
import io.atomix.raft.protocol.InstallRequest;
import io.atomix.raft.protocol.InstallResponse;
import io.atomix.raft.protocol.PollRequest;
import io.atomix.raft.protocol.PollResponse;
import io.atomix.raft.protocol.VoteRequest;
import io.atomix.raft.protocol.VoteResponse;
import io.atomix.raft.storage.log.IndexedRaftRecord;
import io.atomix.raft.utils.Quorum;
import io.atomix.utils.concurrent.Scheduled;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Follower state. */
public final class FollowerRole extends ActiveRole {

  private Scheduled heartbeatTimer;
  private final ClusterMembershipEventListener clusterListener = this::handleClusterEvent;
  private long lastHeartbeat;

  public FollowerRole(final RaftContext context) {
    super(context);
  }

  @Override
  public synchronized CompletableFuture<RaftRole> start() {

    if (raft.getCluster().getActiveMemberStates().isEmpty()) {
      log.info("Single member cluster. Transitioning directly to candidate.");
      raft.transition(RaftServer.Role.CANDIDATE);
      return CompletableFuture.completedFuture(this);
    }

    raft.getMembershipService().addListener(clusterListener);
    return super.start().thenRun(this::resetHeartbeatTimeout).thenApply(v -> this);
  }

  @Override
  public synchronized CompletableFuture<Void> stop() {
    raft.getMembershipService().removeListener(clusterListener);
    cancelHeartbeatTimer();

    return super.stop();
  }

  @Override
  public RaftServer.Role role() {
    return RaftServer.Role.FOLLOWER;
  }

  @Override
  public CompletableFuture<InstallResponse> onInstall(final InstallRequest request) {
    final CompletableFuture<InstallResponse> future = super.onInstall(request);
    if (isRequestFromCurrentLeader(request.currentTerm(), request.leader())) {
      resetHeartbeatTimeout();
    }
    return future;
  }

  /** Cancels the heartbeat timer. */
  private void cancelHeartbeatTimer() {
    if (heartbeatTimer != null) {
      log.trace("Cancelling heartbeat timer");
      heartbeatTimer.cancel();
      heartbeatTimer = null;
    }
  }

  /** Handles a cluster event. */
  private void handleClusterEvent(final ClusterMembershipEvent event) {
    raft.getThreadContext()
        .execute(
            () -> {
              final RaftMember leader = raft.getLeader();
              if (leader != null
                  && event.type() == ClusterMembershipEvent.Type.MEMBER_REMOVED
                  && event.subject().id().equals(leader.memberId())) {
                log.info(
                    "Known leader {} was removed from cluster, sending poll requests",
                    leader.memberId());
                raft.setLeader(null);
                sendPollRequests();
              }
            });
  }

  /**
   * Polls all members of the cluster to determine whether this member should transition to the
   * CANDIDATE state.
   */
  private void sendPollRequests() {
    // Create a quorum that will track the number of nodes that have responded to the poll request.
    final AtomicBoolean complete = new AtomicBoolean();
    final Set<DefaultRaftMember> votingMembers =
        raft.getCluster().getActiveMemberStates().stream()
            .map(RaftMemberContext::getMember)
            .collect(Collectors.toSet());

    // If there are no other members in the cluster, immediately transition to leader.
    if (votingMembers.isEmpty()) {
      raft.transition(RaftServer.Role.CANDIDATE);
      return;
    }

    final Quorum quorum =
        new Quorum(
            raft.getCluster().getQuorum(),
            elected -> {
              // If a majority of the cluster indicated they would vote for us then transition to
              // candidate.
              complete.set(true);
              if (raft.getLeader() == null && elected) {
                raft.transition(RaftServer.Role.CANDIDATE);
              } else {
                resetHeartbeatTimeout();
              }
            });

    // First, load the last log entry to get its term. We load the entry
    // by its index since the index is required by the protocol.
    final IndexedRaftRecord lastEntry = raft.getLog().getLastEntry();

    final long lastTerm;
    if (lastEntry != null) {
      lastTerm = lastEntry.entry().term();
    } else {
      lastTerm = 0;
    }

    log.debug("Polling members {}", votingMembers);

    // Once we got the last log term, iterate through each current member
    // of the cluster and vote each member for a vote.
    for (final DefaultRaftMember member : votingMembers) {
      log.debug("Polling {} for next term {}", member, raft.getTerm() + 1);
      final PollRequest request =
          PollRequest.builder()
              .withTerm(raft.getTerm())
              .withCandidate(raft.getCluster().getLocalMember().memberId())
              .withLastLogIndex(lastEntry != null ? lastEntry.index() : 0)
              .withLastLogTerm(lastTerm)
              .build();
      raft.getProtocol()
          .poll(member.memberId(), request)
          .whenCompleteAsync(
              (response, error) -> handlePollResponse(complete, quorum, member, response, error),
              raft.getThreadContext());
    }
  }

  @Override
  public CompletableFuture<ConfigureResponse> onConfigure(final ConfigureRequest request) {
    final CompletableFuture<ConfigureResponse> future = super.onConfigure(request);
    if (isRequestFromCurrentLeader(request.term(), request.leader())) {
      resetHeartbeatTimeout();
    }
    return future;
  }

  @Override
  public CompletableFuture<AppendResponse> onAppend(final AppendRequest request) {
    final CompletableFuture<AppendResponse> future = super.onAppend(request);
    if (isRequestFromCurrentLeader(request.term(), request.leader())) {
      resetHeartbeatTimeout();
    }
    return future;
  }

  @Override
  protected VoteResponse handleVote(final VoteRequest request) {
    // Reset the heartbeat timeout if we voted for another candidate.
    final VoteResponse response = super.handleVote(request);
    if (response.voted()) {
      resetHeartbeatTimeout();
    }
    return response;
  }

  private boolean isRequestFromCurrentLeader(final long term, final MemberId leader) {
    final long currentTerm = raft.getTerm();
    final RaftMember currentLeader = raft.getLeader();
    if (term < currentTerm
        || (term == currentTerm
            && (currentLeader == null || !leader.equals(currentLeader.memberId())))) {
      log.debug(
          "Expected heartbeat from {} in term {}, but received one from {} in term {}, ignoring it",
          currentLeader,
          currentTerm,
          leader,
          leader);
      return false;
    }
    return true;
  }

  private void resetHeartbeatTimeout() {
    raft.checkThread();
    if (!isRunning()) {
      return;
    }

    cancelHeartbeatTimer();
    updateHeartbeat(System.currentTimeMillis());

    // Set the election timeout in a semi-random fashion with the random range
    // being election timeout and 2 * election timeout.
    final Duration delay =
        raft.getElectionTimeout()
            .plus(
                Duration.ofMillis(
                    raft.getRandom().nextInt((int) raft.getElectionTimeout().toMillis())));
    heartbeatTimer = raft.getThreadContext().schedule(delay, () -> onHeartbeatTimeout(delay));
  }

  private void updateHeartbeat(final long currentTimestamp) {
    if (lastHeartbeat > 0) {
      raft.getRaftRoleMetrics().observeHeartbeatInterval(currentTimestamp - lastHeartbeat);
    }

    lastHeartbeat = currentTimestamp;
  }

  private void schedulePollRequests(final Duration delay) {
    raft.checkThread();

    if (!isRunning()) {
      return;
    }

    if (raft.getFirstCommitIndex() == 0 || raft.getState() == RaftContext.State.READY) {
      final long missTime = System.currentTimeMillis() - lastHeartbeat;
      log.info(
          "No heartbeat from {} in the last {} (calculated from last {} ms), sending poll requests",
          raft.getLeader(),
          delay,
          missTime);
      raft.getRaftRoleMetrics().countHeartbeatMiss();

      raft.setLeader(null);
      sendPollRequests();
    }
  }

  private void onHeartbeatTimeout(final Duration delay) {
    if (!isRunning()) {
      return;
    }

    final Duration pollTimeout = raft.getElectionTimeout();

    heartbeatTimer =
        raft.getThreadContext()
            .schedule(
                pollTimeout,
                () -> {
                  log.debug("Failed to poll a majority of the cluster in {}", pollTimeout);
                  resetHeartbeatTimeout();
                });

    schedulePollRequests(delay);
  }

  private void handlePollResponse(
      final AtomicBoolean complete,
      final Quorum quorum,
      final DefaultRaftMember member,
      final PollResponse response,
      final Throwable error) {
    raft.checkThread();

    if (isRunning() && !complete.get()) {
      if (error != null) {
        log.warn("Poll request to {} failed: {}", member.memberId(), error.getMessage());
        quorum.fail();
      } else {
        if (response.term() > raft.getTerm()) {
          raft.setTerm(response.term());
        }

        if (!response.accepted()) {
          log.debug("Received rejected poll from {}", member);
          quorum.fail();
        } else if (response.term() != raft.getTerm()) {
          log.debug("Received accepted poll for a different term from {}", member);
          quorum.fail();
        } else {
          log.debug("Received accepted poll from {}", member);
          quorum.succeed();
        }
      }
    }
  }
}
