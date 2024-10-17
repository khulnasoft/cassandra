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
package org.apache.cassandra.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.zip.Checksum;

import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;

/**
 * Record keys must satisfy two properties:
 * <p>
 * 1. Must have a fixed serialized size
 * 2. Must be byte-order comparable
 */
public interface KeySupport<K> extends Comparator<K>
{
    int serializedSize(int userVersion);

    void serialize(K key, DataOutputPlus out, int userVersion) throws IOException;
    void serialize(K key, ByteBuffer out, int userVersion) throws IOException;

    K deserialize(DataInputPlus in, int userVersion) throws IOException;

    K deserialize(ByteBuffer buffer, int position, int userVersion);
    K deserialize(ByteBuffer buffer, int userVersion);

    void updateChecksum(Checksum crc, K key, int userVersion);

    int compareWithKeyAt(K key, ByteBuffer buffer, int position, int userVersion);
}
