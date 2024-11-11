/*
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
package org.apache.cassandra.index.sai.disk.v1.kdtree;

import java.nio.ByteOrder;

import org.junit.Test;

import org.apache.cassandra.index.sai.disk.ModernResettableByteBuffersIndexOutput;
import org.apache.cassandra.index.sai.disk.oldlucene.LuceneCompat;
import org.apache.cassandra.index.sai.utils.SaiRandomizedTest;
import org.apache.cassandra.index.sai.utils.SeekingRandomAccessInput;
import org.apache.lucene.util.LongValues;

public class LeafOrderMapTest extends SaiRandomizedTest
{
    @Test
    public void test() throws Exception
    {
        int[] array = new int[1024];
        for (int x=0; x < array.length; x++)
        {
            array[x] = x;
        }
        shuffle(array);

        var out = new ModernResettableByteBuffersIndexOutput(array.length, "");

        LeafOrderMap.write(ByteOrder.LITTLE_ENDIAN, array, array.length, array.length - 1, out);

        var input = out.toIndexInput();

        final byte bits = (byte) LuceneCompat.directWriterUnsignedBitsRequired(ByteOrder.LITTLE_ENDIAN, array.length - 1);
        LongValues reader = LuceneCompat.directReaderGetInstance(new SeekingRandomAccessInput(input, ByteOrder.LITTLE_ENDIAN), bits, 0);

        for (int x=0; x < array.length; x++)
        {
            int value = LeafOrderMap.getValue(x, reader);

            assertEquals("disagreed at " + x, array[x], value);
        }
    }
}
