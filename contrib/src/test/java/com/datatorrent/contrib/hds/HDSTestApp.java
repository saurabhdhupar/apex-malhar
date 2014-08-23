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
package com.datatorrent.contrib.hds;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.junit.Assert;
import org.junit.Test;

import com.datatorrent.api.BaseOperator;
import com.datatorrent.api.DAG;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.InputOperator;
import com.datatorrent.api.LocalMode;
import com.datatorrent.api.StreamingApplication;
import com.datatorrent.api.annotation.ApplicationAnnotation;
import com.datatorrent.common.util.Slice;
import com.datatorrent.lib.util.KeyValPair;
import com.google.common.collect.Maps;

@ApplicationAnnotation(name="HDSTestApp")
public class HDSTestApp implements StreamingApplication
{
  private static final String DATA0 = "data0";
  private static final String DATA1 = "data1";
  private static byte[] KEY0 = ByteBuffer.allocate(16).putLong(0x00000000).putLong(0).array();
  private static byte[] KEY1 = ByteBuffer.allocate(16).putLong(0xffffffff).putLong(0).array();

  public static class Generator extends BaseOperator implements InputOperator
  {
    public transient DefaultOutputPort<KeyValPair<byte[], byte[]>> output = new DefaultOutputPort<KeyValPair<byte[], byte[]>>();

    @Override
    public void emitTuples()
    {
      output.emit(new KeyValPair<byte[], byte[]>(KEY0, DATA0.getBytes()));
      output.emit(new KeyValPair<byte[], byte[]>(KEY1, DATA1.getBytes()));
    }
  }

  @Override
  public void populateDAG(DAG dag, Configuration conf)
  {
    Generator generator = dag.addOperator("Generator", new Generator());
    HDSOperator store = dag.addOperator("Store", new HDSOperator());
    store.setKeyComparator(new HDSTest.SequenceComparator());
    store.setFileStore(new MockFileAccess());
    dag.addStream("Generator2Store", generator.output, store.data);
  }

  @Test
  public void test() throws Exception
  {
    File file = new File("target/hds2");
    FileUtils.deleteDirectory(file);

    LocalMode lma = LocalMode.newInstance();
    Configuration conf = new Configuration(false);
    conf.set("dt.operator.Store.fileStore.basePath", file.toURI().toString());
    //conf.set("dt.operator.Store.flushSize", "0");
    conf.set("dt.operator.Store.flushIntervalCount", "1");
    conf.set("dt.operator.Store.attr.INITIAL_PARTITION_COUNT", "2");

    lma.prepareDAG(new HDSTestApp(), conf);
    LocalMode.Controller lc = lma.getController();
    lc.setHeartbeatMonitoringEnabled(false);
    lc.runAsync();

    long tms = System.currentTimeMillis();
    File f0 = new File(file, "0/0-0");
    File f1 = new File(file, "1/1-0");
    File wal0 = new File(file, "0/_WAL-0");
    File wal1 = new File(file, "1/_WAL-0");

    while (System.currentTimeMillis() - tms < 30000) {
      if (f0.exists() && f1.exists()) break;
      Thread.sleep(100);
    }
    lc.shutdown();

    Assert.assertTrue("exists " + f0, f0.exists() && f0.isFile());
    Assert.assertTrue("exists " + f1, f1.exists() && f1.isFile());
    Assert.assertTrue("exists " + wal0, wal0.exists() && wal0.exists());
    Assert.assertTrue("exists " + wal1, wal1.exists() && wal1.exists());

    HDSFileAccessFSImpl fs = new MockFileAccess();
    fs.setBasePath(file.toURI().toString());
    fs.init();

    TreeMap<Slice, byte[]> data = Maps.newTreeMap(new HDSTest.SequenceComparator());
    fs.getReader(0, "0-0").readFully(data);
    Assert.assertArrayEquals("read key=" + new String(KEY0), DATA0.getBytes(), data.get(HDSBucketManager.toSlice(KEY0)));

    data.clear();
    fs.getReader(1, "1-0").readFully(data);
    Assert.assertArrayEquals("read key=" + new String(KEY1), DATA1.getBytes(), data.get(HDSBucketManager.toSlice(KEY1)));

    fs.close();
  }

}
