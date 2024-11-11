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
package org.apache.cassandra.index.sai.memory;

import java.io.IOException;

import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.index.sai.iterators.KeyRangeIterator;
import org.apache.cassandra.index.sai.utils.PrimaryKey;

/**
 * A {@link KeyRangeIterator} that filters the returned {@link PrimaryKey}s based on the provided keyRange
 */
public class FilteringKeyRangeIterator extends KeyRangeIterator
{
    private final AbstractBounds<PartitionPosition> keyRange;
    private final KeyRangeIterator source;

    public FilteringKeyRangeIterator(KeyRangeIterator source, AbstractBounds<PartitionPosition> keyRange)
    {
        super(source.getMinimum(), source.getMaximum(), source.getMaxKeys());
        this.keyRange = keyRange;
        this.source = source;
    }

    @Override
    protected PrimaryKey computeNext()
    {
        while (source.hasNext())
        {
            PrimaryKey key = source.next();
            if (keyRange.contains(key.partitionKey()))
                return key;
        }
        return endOfData();
    }

    @Override
    protected void performSkipTo(PrimaryKey nextKey)
    {
        source.skipTo(nextKey);
    }

    @Override
    public void close() throws IOException
    {
    }
}
