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
package org.apache.cassandra.utils;

import java.io.Closeable;
import java.util.Collections;
import java.util.Iterator;

import org.apache.cassandra.io.util.FileUtils;


// so we can instantiate anonymous classes implementing both interfaces
public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable
{
    public void close();

    CloseableIterator<Object> EMPTY = CloseableIterator.wrap(Collections.emptyIterator());

    /**
     * Returns an empty {@link CloseableIterator}.
     */
    @SuppressWarnings("unchecked")
    static <T> CloseableIterator<T> emptyIterator()
    {
        return (CloseableIterator<T>) EMPTY;
    }

    /**
     * Wraps an {@link Iterator} making it a {@link CloseableIterator}.
     */
    static <T> CloseableIterator<T> wrap(Iterator<T> iterator)
    {
        return new CloseableIterator<>()
        {
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            public T next()
            {
                return iterator.next();
            }

            public void remove()
            {
                iterator.remove();
            }

            public void close()
            {
            }
        };
    }

    /**
     * Pairs a {@link CloseableIterator} and an {@link AutoCloseable} so that the latter is closed when the former is
     * closed.
     */
    static <T> CloseableIterator<T> withOnClose(CloseableIterator<T> iterator, Closeable onClose)
    {
        return new CloseableIterator<>()
        {
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            public T next()
            {
                return iterator.next();
            }

            public void remove()
            {
                iterator.remove();
            }

            public void close()
            {
                iterator.close();
                FileUtils.closeQuietly(onClose);
            }
        };
    }
}
