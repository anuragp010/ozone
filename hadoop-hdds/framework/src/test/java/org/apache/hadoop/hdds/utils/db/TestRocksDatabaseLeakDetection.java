/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.utils.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.hdds.utils.db.managed.ManagedDBOptions;
import org.apache.hadoop.hdds.utils.db.managed.ManagedRocksDB;
import org.apache.hadoop.hdds.utils.db.managed.ManagedWriteOptions;
import org.apache.ozone.test.GenericTestUtils.LogCapturer;
import org.junit.jupiter.api.Test;

/**
 * Test {@link RocksDatabase}'s leak detection: the database registers with a shared LeakDetector
 * and warns if it is garbage collected without being closed.
 */
class TestRocksDatabaseLeakDetection {

  /** The reporter warns only when the database was not closed. Exercised directly, no native DB. */
  @Test
  void reporterWarnsWhenNotClosed() {
    try (LogCapturer logs = LogCapturer.captureLogs(RocksDatabase.class)) {
      RocksDatabase.newLeakReporter(new AtomicBoolean(false), "db-1",
          new Throwable("creation stack trace")).run();
      assertThat(logs.getOutput()).contains("db-1 is not closed properly");
    }
  }

  @Test
  void reporterSilentWhenClosed() {
    try (LogCapturer logs = LogCapturer.captureLogs(RocksDatabase.class)) {
      RocksDatabase.newLeakReporter(new AtomicBoolean(true), "db-1",
          new Throwable("creation stack trace")).run();
      assertThat(logs.getOutput()).doesNotContain("is not closed properly");
    }
  }

  /**
   * Drive an actual {@link RocksDatabase} instance through GC without closing it and verify the
   * leak is detected. The native RocksDB is mocked and no database is opened: with empty column
   * family lists the constructor never dereferences the native handles, so this exercises the
   * constructor's LeakDetector registration and the GC-triggered report without native resources.
   */
  @Test
  void leakDetectedForUnclosedDatabase() throws Exception {
    try (LogCapturer logs = LogCapturer.captureLogs(RocksDatabase.class)) {
      Constructor<RocksDatabase> ctor = RocksDatabase.class.getDeclaredConstructor(
          File.class, ManagedRocksDB.class, ManagedDBOptions.class,
          ManagedWriteOptions.class, List.class, List.class);
      ctor.setAccessible(true);
      RocksDatabase db = ctor.newInstance(new File("leak-test"), mock(ManagedRocksDB.class),
          null, null, Collections.emptyList(), Collections.emptyList());
      assertThat(db).isNotNull();

      // Drop the only strong reference; the reporter captures no reference back to the database,
      // so it becomes collectible. The report runs asynchronously on the LeakDetector thread.
      db = null;
      for (int i = 0; i < 50 && !logs.getOutput().contains("is not closed properly"); i++) {
        System.gc();
        Thread.sleep(100);
      }
      assertThat(logs.getOutput()).contains("is not closed properly");
    }
  }
}