/*
 * Copyright (c) 2014 DataTorrent, Inc. ALL Rights Reserved.
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
package com.datatorrent.lib.dedup;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import com.datatorrent.api.DAG;

import com.datatorrent.lib.bucket.*;
import com.datatorrent.lib.helper.OperatorContextTestHelper;
import com.datatorrent.lib.testbench.CollectorTestSink;
import com.datatorrent.lib.util.TestUtils;
import java.util.ArrayList;

/**
 * Tests for {@link Deduper}

public class CustomDeduperTest
{
  private static final Logger logger = LoggerFactory.getLogger(CustomDeduperTest.class);

  private final static String APPLICATION_PATH_PREFIX = "target/DeduperTest";
  private final static String APP_ID = "DeduperTest";
  private final static int OPERATOR_ID = 0;

  private final static Exchanger<Long> eventBucketExchanger = new Exchanger<Long>();

  private static class DummyCustomDeduper extends CustomDeduper<DummyCustomEvent, DummyCustomEvent>
  {

    @Override
    public void bucketLoaded(Bucket<DummyCustomEvent> bucket)
    {
      try {
        super.bucketLoaded(bucket);
        eventBucketExchanger.exchange(bucket.bucketKey);
      }
      catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public DummyCustomEvent convert(DummyCustomEvent dummyEvent)
    {
      return dummyEvent;
    }

    public void addEventManuallyToWaiting(DummyCustomEvent event)
    {
      waitingEvents.put(bucketManager.getBucketKeyFor(event), Lists.newArrayList(event));
    }
  }

  private static DummyCustomDeduper deduper;
  private static String applicationPath;

  @Test
  public void testDedup()
  {
    List<DummyCustomEvent> events = Lists.newArrayList();
    Calendar calendar = Calendar.getInstance();
    ArrayList<String> temp = new ArrayList<String>();
    for (int i = 0; i < 10; i++){
      temp.add(i+"");
      events.add(new DummyCustomEvent(temp, calendar.getTimeInMillis()));
    }
    events.add(new DummyCustomEvent(temp, calendar.getTimeInMillis()));

    com.datatorrent.api.Attribute.AttributeMap.DefaultAttributeMap attributes = new com.datatorrent.api.Attribute.AttributeMap.DefaultAttributeMap();
    attributes.put(DAG.APPLICATION_ID, APP_ID);
    attributes.put(DAG.APPLICATION_PATH, applicationPath);

    deduper.setup(new OperatorContextTestHelper.TestIdOperatorContext(OPERATOR_ID, attributes));
    CollectorTestSink<DummyCustomEvent> collectorTestSink = new CollectorTestSink<DummyCustomEvent>();
    TestUtils.setSink(deduper.output, collectorTestSink);

    logger.debug("start round 0");
    deduper.beginWindow(0);
    testRound(events);
    deduper.handleIdleTime();
    deduper.endWindow();
    Assert.assertEquals("output tuples", 10, collectorTestSink.collectedTuples.size());
    collectorTestSink.clear();
    logger.debug("end round 0");

    logger.debug("start round 1");
    deduper.beginWindow(1);
    testRound(events);
    deduper.handleIdleTime();
    deduper.endWindow();
    Assert.assertEquals("output tuples", 0, collectorTestSink.collectedTuples.size());
    collectorTestSink.clear();
    logger.debug("end round 1");

    //Test the sliding window
    try {
      Thread.sleep(1500);
    }
    catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    deduper.handleIdleTime();
    long now = System.currentTimeMillis();
    for (int i = 10; i < 15; i++) {
//      events.add(new DummyCustomEvent(i, now));
    }

    logger.debug("start round 2");
    deduper.beginWindow(2);
    testRound(events);
    deduper.handleIdleTime();
    deduper.endWindow();
    Assert.assertEquals("output tuples", 5, collectorTestSink.collectedTuples.size());
    collectorTestSink.clear();
    logger.debug("end round 2");
    deduper.teardown();
  }

  private void testRound(List<DummyCustomEvent> events)
  {
    for (DummyCustomEvent event : events) {
      deduper.input.process(event);
    }
    try {
      eventBucketExchanger.exchange(null, 1, TimeUnit.SECONDS);
    }
    catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    catch (TimeoutException e) {
      logger.debug("Timeout Happened");
    }
  }

  @Test
  public void testDeduperRedeploy() throws Exception
  {
    com.datatorrent.api.Attribute.AttributeMap.DefaultAttributeMap attributes = new com.datatorrent.api.Attribute.AttributeMap.DefaultAttributeMap();
    attributes.put(DAG.APPLICATION_ID, APP_ID);
    attributes.put(DAG.APPLICATION_PATH, applicationPath);

//    deduper.addEventManuallyToWaiting(new DummyCustomEvent(100, System.currentTimeMillis()));
    deduper.setup(new OperatorContextTestHelper.TestIdOperatorContext(0, attributes));
    eventBucketExchanger.exchange(null, 500, TimeUnit.MILLISECONDS);
    deduper.endWindow();
    deduper.teardown();
  }

  @BeforeClass
  public static void setup()
  {
    applicationPath = OperatorContextTestHelper.getUniqueApplicationPath(APPLICATION_PATH_PREFIX);
    ExpirableHdfsBucketStore<DummyCustomEvent>  bucketStore = new ExpirableHdfsBucketStore<DummyCustomEvent>();
    deduper = new DummyCustomDeduper();
    TimeBasedBucketManagerImpl<DummyCustomEvent> storageManager = new TimeBasedBucketManagerImpl<DummyCustomEvent>();
    storageManager.setBucketSpanInMillis(1000);
    storageManager.setMillisPreventingBucketEviction(60000);
    storageManager.setBucketStore(bucketStore);
    deduper.setBucketManager(storageManager);
  }

  @AfterClass
  public static void teardown()
  {
    Path root = new Path(applicationPath);
    try {
      FileSystem fs = FileSystem.newInstance(root.toUri(), new Configuration());
      fs.delete(root, true);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}*/