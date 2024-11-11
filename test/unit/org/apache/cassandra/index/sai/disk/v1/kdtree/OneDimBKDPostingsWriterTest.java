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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.agrona.collections.IntArrayList;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.SAITester;
import org.apache.cassandra.index.sai.disk.PostingList;
import org.apache.cassandra.index.sai.disk.format.IndexComponents;
import org.apache.cassandra.index.sai.disk.format.IndexComponentType;
import org.apache.cassandra.index.sai.disk.format.IndexComponent;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.disk.io.IndexInput;
import org.apache.cassandra.index.sai.disk.io.IndexOutputWriter;
import org.apache.cassandra.index.sai.disk.v1.IndexWriterConfig;
import org.apache.cassandra.index.sai.disk.v1.postings.PostingsReader;
import org.apache.cassandra.index.sai.postings.IntArrayPostingList;
import org.apache.cassandra.index.sai.utils.SaiRandomizedTest;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedLongValues;

import static org.apache.cassandra.index.sai.metrics.QueryEventListeners.NO_OP_POSTINGS_LISTENER;

public class OneDimBKDPostingsWriterTest extends SaiRandomizedTest
{
    private IndexDescriptor indexDescriptor;
    private String index;
    private IndexContext indexContext;

    @Before
    public void setup() throws Throwable
    {
        indexDescriptor = newIndexDescriptor();
        index = newIndex();
        indexContext = SAITester.createIndexContext(index, Int32Type.instance);
    }

    @Test
    public void shouldWritePostingsForEligibleNodes() throws IOException
    {
        List<PackedLongValues> leaves =
        Arrays.asList(postings(1, 5, 7), postings(3, 4, 6), postings(2, 8, 10), postings(11, 12, 13));

        IndexComponents.ForWrite components = indexDescriptor.newPerIndexComponentsForWrite(indexContext);
        OneDimBKDPostingsWriter writer = new OneDimBKDPostingsWriter(leaves, new IndexWriterConfig("test", 2, 1), components::logMessage);

        // should build postings for nodes 2 & 3 (lvl 2) and 8, 10, 12, 14 (lvl 4)
        writer.onLeaf(64, 1, pathToRoot(1, 2, 4, 8, 16));
        writer.onLeaf(80, 2, pathToRoot(1, 2, 5, 10, 20));
        writer.onLeaf(96, 3, pathToRoot(1, 3, 6, 12, 24));
        writer.onLeaf(112, 4, pathToRoot(1, 3, 7, 14, 28));

        long fp;
        try (IndexOutputWriter output = components.addOrGet(IndexComponentType.KD_TREE_POSTING_LISTS).openOutput())
        {
            fp = writer.finish(output);
        }

        IndexComponent.ForRead kdTreePostings = components.get(IndexComponentType.KD_TREE_POSTING_LISTS);
        BKDPostingsIndex postingsIndex = new BKDPostingsIndex(kdTreePostings.createFileHandle(), fp);
        assertEquals(10, postingsIndex.size());

        // Internal postings...
        assertTrue(postingsIndex.exists(2));
        assertTrue(postingsIndex.exists(3));
        assertTrue(postingsIndex.exists(8));
        assertTrue(postingsIndex.exists(10));
        assertTrue(postingsIndex.exists(12));
        assertTrue(postingsIndex.exists(14));

        assertPostingReaderEquals(kdTreePostings, postingsIndex, 2, new int[]{ 1, 3, 4, 5, 6, 7 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 3, new int[]{ 2, 8, 10, 11, 12, 13 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 8, new int[]{ 1, 5, 7 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 10, new int[]{ 3, 4, 6 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 12, new int[]{ 2, 8, 10 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 14, new int[]{ 11, 12, 13 });

        // Leaf postings...
        assertTrue(postingsIndex.exists(64));
        assertTrue(postingsIndex.exists(80));
        assertTrue(postingsIndex.exists(96));
        assertTrue(postingsIndex.exists(112));

        assertPostingReaderEquals(kdTreePostings, postingsIndex, 64, new int[]{ 1, 5, 7 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 80, new int[]{ 3, 4, 6 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 96, new int[]{ 2, 8, 10 });
        assertPostingReaderEquals(kdTreePostings, postingsIndex, 112, new int[]{ 11, 12, 13 });
    }

    @Test
    public void shouldSkipPostingListWhenSamplingMisses() throws IOException
    {
        List<PackedLongValues> leaves = Collections.singletonList(postings(1, 2, 3));
        IndexComponents.ForWrite components = indexDescriptor.newPerIndexComponentsForWrite(indexContext);
        OneDimBKDPostingsWriter writer = new OneDimBKDPostingsWriter(leaves, new IndexWriterConfig("test", 5, 1), components::logMessage);

        // The tree is too short to have any internal posting lists.
        writer.onLeaf(16, 1, pathToRoot(1, 2, 4, 8));

        long fp;
        try (IndexOutputWriter output = components.addOrGet(IndexComponentType.KD_TREE_POSTING_LISTS).openOutput())
        {
            fp = writer.finish(output);
        }

        // There is only a single posting list...the leaf posting list.
        BKDPostingsIndex postingsIndex = new BKDPostingsIndex(components.get(IndexComponentType.KD_TREE_POSTING_LISTS).createFileHandle(), fp);
        assertEquals(1, postingsIndex.size());
    }

    @Test
    public void shouldSkipPostingListWhenTooFewLeaves() throws IOException
    {
        List<PackedLongValues> leaves = Collections.singletonList(postings(1, 2, 3));
        IndexComponents.ForWrite components = indexDescriptor.newPerIndexComponentsForWrite(indexContext);
        OneDimBKDPostingsWriter writer = new OneDimBKDPostingsWriter(leaves, new IndexWriterConfig("test", 2, 2), components::logMessage);

        // The tree is too short to have any internal posting lists.
        writer.onLeaf(16, 1, pathToRoot(1, 2, 4, 8));

        long fp;
        try (IndexOutputWriter output = components.addOrGet(IndexComponentType.KD_TREE_POSTING_LISTS).openOutput())
        {
            fp = writer.finish(output);
        }

        // There is only a single posting list...the leaf posting list.
        BKDPostingsIndex postingsIndex = new BKDPostingsIndex(components.get(IndexComponentType.KD_TREE_POSTING_LISTS).createFileHandle(), fp);
        assertEquals(1, postingsIndex.size());
    }

    private void assertPostingReaderEquals(IndexComponent.ForRead kdTreePostingLists, BKDPostingsIndex postingsIndex, int nodeID, int[] postings) throws IOException
    {
        assertPostingReaderEquals(kdTreePostingLists.openInput(),
                                  postingsIndex.getPostingsFilePointer(nodeID),
                                  new IntArrayPostingList(postings));
    }

    private void assertPostingReaderEquals(IndexInput input, long offset, PostingList expected) throws IOException
    {
        try (PostingsReader reader = new PostingsReader(input, offset, NO_OP_POSTINGS_LISTENER))
        {
            assertPostingListEquals(expected, reader);
        }
    }

    private PackedLongValues postings(int... postings)
    {
        final PackedLongValues.Builder builder = PackedLongValues.deltaPackedBuilder(PackedInts.COMPACT);
        for (int posting : postings)
        {
            builder.add(posting);
        }
        return builder.build();
    }

    private IntArrayList pathToRoot(int... nodes)
    {
        final IntArrayList path = new IntArrayList();
        for (int node : nodes)
        {
            path.add(node);
        }
        return path;
    }
}
