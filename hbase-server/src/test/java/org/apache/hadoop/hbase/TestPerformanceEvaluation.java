/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.GsonUtil;
import org.apache.hbase.thirdparty.com.google.gson.Gson;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestPerformanceEvaluation {

  @Test
  public void testSerialization() throws IOException {
    PerformanceEvaluation.TestOptions options = new PerformanceEvaluation.TestOptions();
    assertTrue(!options.isAutoFlush());
    options.setAutoFlush(true);
    Gson gson = GsonUtil.createGson().create();
    String optionsString = gson.toJson(options);
    PerformanceEvaluation.TestOptions optionsDeserialized =
      gson.fromJson(optionsString, PerformanceEvaluation.TestOptions.class);
    assertTrue(optionsDeserialized.isAutoFlush());
  }

  @Test
  public void testSetBufferSizeOption() {
    PerformanceEvaluation.TestOptions opts = new PerformanceEvaluation.TestOptions();
    long bufferSize = opts.getBufferSize();
    assertEquals(bufferSize, 2l * 1024l * 1024l);
    opts.setBufferSize(64l * 1024l);
    bufferSize = opts.getBufferSize();
    assertEquals(bufferSize, 64l * 1024l);
  }
}
