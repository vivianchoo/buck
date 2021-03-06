/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.event.listener;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleDurationTracker;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class DistBuildSlaveEventBusListenerTest {

  private DistBuildSlaveEventBusListener listener;
  private RunId runId;
  private StampedeId stampedeId;
  private DistBuildService distBuildServiceMock;
  private BuckEventBus eventBus;
  private SettableFakeClock clock = new SettableFakeClock(0, 0);

  @Before
  public void setUp() {
    runId = new RunId();
    runId.setId("this-is-the-slaves-id");
    stampedeId = new StampedeId();
    stampedeId.setId("this-is-the-big-id");
    distBuildServiceMock = EasyMock.createMock(DistBuildService.class);
    eventBus = BuckEventBusFactory.newInstance();
  }

  private void setUpDistBuildSlaveEventBusListener() {
    listener = new DistBuildSlaveEventBusListener(
        stampedeId,
        runId,
        clock,
        Executors.newScheduledThreadPool(1),
        1);
    eventBus.register(listener);
    listener.setDistBuildService(distBuildServiceMock);
  }

  @After
  public void tearDown() throws IOException {
    listener.close();
  }

  @Test
  public void testHandlingConsoleEvents() throws IOException {
    List<ConsoleEvent> consoleEvents = new LinkedList<>();
    consoleEvents.add(ConsoleEvent.create(Level.FINE, "fine message"));
    consoleEvents.add(ConsoleEvent.create(Level.INFO, "info message"));
    consoleEvents.add(ConsoleEvent.create(Level.WARNING, "warning message"));
    consoleEvents.add(ConsoleEvent.create(Level.SEVERE, "severe message"));
    consoleEvents.add(ConsoleEvent.create(Level.INFO, "info message"));
    consoleEvents.add(ConsoleEvent.create(Level.SEVERE, "severe message"));

    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(runId), anyObject());
    expectLastCall().anyTimes();

    Capture<List<BuildSlaveConsoleEvent>> capturedEventLists = Capture.newInstance(CaptureType.ALL);
    distBuildServiceMock.uploadBuildSlaveConsoleEvents(
        eq(stampedeId),
        eq(runId),
        capture(capturedEventLists));
    expectLastCall().atLeastOnce();
    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    for (int i = 0; i < consoleEvents.size(); ++i) {
      clock.setCurrentTimeMillis(10 * i);
      eventBus.post(consoleEvents.get(i));
    }

    listener.close();
    verify(distBuildServiceMock);

    List<BuildSlaveConsoleEvent> capturedEvents =
        capturedEventLists.getValues().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    Assert.assertEquals(capturedEvents.size(), 3);
    Assert.assertTrue(capturedEvents.containsAll(ImmutableList.of(
        DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(2), 20),
        DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(3), 30),
        DistBuildUtil.createBuildSlaveConsoleEvent(consoleEvents.get(5), 50))));
  }

  @Test
  public void testHandlingRuleCountCalculatedEvent() throws IOException {
    BuildSlaveStatus expectedStatus = new BuildSlaveStatus();
    expectedStatus.setStampedeId(stampedeId);
    expectedStatus.setRunId(runId);
    expectedStatus.setTotalRulesCount(100);

    distBuildServiceMock.uploadBuildSlaveConsoleEvents(eq(stampedeId), eq(runId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(runId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    eventBus.post(BuildEvent.ruleCountCalculated(ImmutableSet.of(), 100));

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testHandlingRuleCountUpdateEvent() throws IOException {
    BuildSlaveStatus expectedStatus = new BuildSlaveStatus();
    expectedStatus.setStampedeId(stampedeId);
    expectedStatus.setRunId(runId);
    expectedStatus.setTotalRulesCount(50);

    distBuildServiceMock.uploadBuildSlaveConsoleEvents(eq(stampedeId), eq(runId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(runId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    eventBus.post(BuildEvent.ruleCountCalculated(ImmutableSet.of(), 100));
    eventBus.post(BuildEvent.unskippedRuleCountUpdated(50));

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }

  @Test
  public void testHandlingBuildRuleEvents() throws IOException {
    BuildSlaveStatus expectedStatus = new BuildSlaveStatus();
    expectedStatus.setStampedeId(stampedeId);
    expectedStatus.setRunId(runId);
    expectedStatus.setRulesStartedCount(1);
    expectedStatus.setRulesFinishedCount(5);
    expectedStatus.setRulesSuccessCount(3);
    expectedStatus.setRulesFailureCount(1);
    expectedStatus.setCacheHitsCount(1);
    expectedStatus.setCacheMissesCount(1);
    expectedStatus.setCacheErrorsCount(1);
    expectedStatus.setCacheIgnoresCount(2);

    distBuildServiceMock.uploadBuildSlaveConsoleEvents(eq(stampedeId), eq(runId), anyObject());
    expectLastCall().anyTimes();

    Capture<BuildSlaveStatus> capturedStatus = Capture.newInstance(CaptureType.LAST);
    distBuildServiceMock.updateBuildSlaveStatus(eq(stampedeId), eq(runId), capture(capturedStatus));
    expectLastCall().atLeastOnce();

    replay(distBuildServiceMock);
    setUpDistBuildSlaveEventBusListener();

    RuleKey fakeRuleKey = new RuleKey("aaaa");
    BuildTarget fakeTarget = BuildTargetFactory.newInstance("//some:target");
    FakeRuleKeyFactory fakeRuleKeyFactory =
        new FakeRuleKeyFactory(ImmutableMap.of(fakeTarget, fakeRuleKey));
    FakeBuildRule fakeRule = new FakeBuildRule(fakeTarget.getFullyQualifiedName());
    BuildRuleDurationTracker tracker = new BuildRuleDurationTracker();

    BuildRuleEvent.Started started1 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started2 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started3 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started4 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started5 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started6 = BuildRuleEvent.started(fakeRule, tracker);
    BuildRuleEvent.Started started7 = BuildRuleEvent.started(fakeRule, tracker);

    BuildRuleEvent.Suspended suspended3 = BuildRuleEvent.suspended(started3, fakeRuleKeyFactory);
    BuildRuleEvent.Resumed resumed3 = BuildRuleEvent.resumed(fakeRule, tracker, fakeRuleKeyFactory);
    BuildRuleEvent.Suspended suspended7 = BuildRuleEvent.suspended(started7, fakeRuleKeyFactory);

    eventBus.post(started1);
    eventBus.post(started2);
    eventBus.post(
        BuildRuleEvent.finished(
            started1,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.hit("buckcache"),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(started3);
    eventBus.post(
        BuildRuleEvent.finished(
            started2,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(suspended3);
    eventBus.post(started4);
    eventBus.post(started5);
    eventBus.post(
        BuildRuleEvent.finished(
            started5,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.FAIL,
            CacheResult.error("buckcache", "connection error"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(
        BuildRuleEvent.finished(
            started4,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.CANCELED,
            CacheResult.ignored(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(started6);
    eventBus.post(started7);
    eventBus.post(resumed3);
    eventBus.post(
        BuildRuleEvent.finished(
            started6,
            BuildRuleKeys.of(fakeRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.ignored(),
            Optional.empty(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    eventBus.post(suspended7);

    listener.close();
    verify(distBuildServiceMock);
    Assert.assertEquals(capturedStatus.getValue(), expectedStatus);
  }
}
