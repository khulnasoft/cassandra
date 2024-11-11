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

package org.apache.cassandra.distributed.test.sai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.test.TestBaseImpl;
import org.apache.cassandra.index.sai.SAITester;
import org.apache.cassandra.index.sai.cql.GeoDistanceAccuracyTest;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.apache.cassandra.index.sai.cql.VectorTester;
import org.apache.cassandra.index.sai.disk.vector.VectorSourceModel;

import static org.apache.cassandra.distributed.api.Feature.GOSSIP;
import static org.apache.cassandra.distributed.api.Feature.NETWORK;
import static org.apache.cassandra.index.sai.SAITester.getRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class VectorDistributedTest extends TestBaseImpl
{
    private static final Logger logger = LoggerFactory.getLogger(VectorDistributedTest.class);
    private static final VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();

    @Rule
    public SAITester.FailureWatcher failureRule = new SAITester.FailureWatcher();

    private static final String CREATE_KEYSPACE = "CREATE KEYSPACE %%s WITH replication = {'class': 'SimpleStrategy', 'replication_factor': %d}";
    private static final String CREATE_TABLE = "CREATE TABLE %%s (pk int primary key, val vector<float, %d>)";
    private static final String CREATE_INDEX = "CREATE CUSTOM INDEX ON %%s(%s) USING 'StorageAttachedIndex'";

    private static final VectorSimilarityFunction function = VectorSourceModel.OTHER.defaultSimilarityFunction;

    private static final double MIN_RECALL_AVG = 0.8;
    // Multiple runs of the geo search test shows the recall test results in between 89% and 97%
    private static final double MIN_GEO_SEARCH_RECALL = 0.85;

    private static final int NUM_REPLICAS = 3;
    private static final int RF = 2;

    private static final AtomicInteger seq = new AtomicInteger();
    private static String table;

    private static Cluster cluster;

    private static int dimensionCount;

    @BeforeClass
    public static void setupCluster() throws Exception
    {
        cluster = Cluster.build(NUM_REPLICAS)
                         .withTokenCount(1) // VSTODO in-jvm-test in CC branch doesn't support multiple tokens
                         .withDataDirCount(1) // VSTODO vector memtable flush doesn't support multiple directories yet
                         .withConfig(config -> config.with(GOSSIP).with(NETWORK))
                         .start();

        cluster.schemaChange(withKeyspace(String.format(CREATE_KEYSPACE, RF)));
    }

    @AfterClass
    public static void closeCluster()
    {
        if (cluster != null)
            cluster.close();
    }

    @Before
    public void before()
    {
        table = "table_" + seq.getAndIncrement();
        dimensionCount = getRandom().nextIntBetween(100, 2048);
    }

    @After
    public void after()
    {
        cluster.schemaChange(formatQuery("DROP TABLE IF EXISTS %s"));
    }

    @Test
    public void testVectorSearch()
    {
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val")));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);

        int vectorCount = getRandom().nextIntBetween(500, 1000);
        List<float[]> vectors = generateVectors(vectorCount);

        int pk = 0;
        for (float[] vector : vectors)
            execute("INSERT INTO %s (pk, val) VALUES (" + (pk++) + ", " + vectorString(vector) + " )");

        // query memtable index
        double memtableRecall = testMultiple((__) ->
        {
            float[] queryVector = randomVector();
            int limit = Math.min(getRandom().nextIntBetween(10, 50), vectors.size());
            Object[][] result = searchWithLimit(queryVector, limit);
            return computeRecall(queryVector, vectors, getVectors(result));
        });
        assertThat(memtableRecall).isGreaterThanOrEqualTo(MIN_RECALL_AVG);

        double memtableRecallWithPaging = testMultiple((__) -> {
            float[] queryVector = randomVector();
            int pageSize = getRandom().nextIntBetween(40, 70);
            var limit = getRandom().nextIntBetween(20, 50);
            var result = searchWithPageAndLimit(queryVector, pageSize, limit);
            return computeRecall(queryVector, vectors, getVectors(result));
        });
        assertThat(memtableRecallWithPaging).isGreaterThanOrEqualTo(MIN_RECALL_AVG);

        // query on-disk index
        cluster.forEach(n -> n.flush(KEYSPACE));

        double sstableRecall = testMultiple((__) ->
        {
            float[] queryVector = randomVector();
            var limit = Math.min(getRandom().nextIntBetween(20, 50), vectors.size());
            var result = searchWithLimit(queryVector, limit);
            return computeRecall(queryVector, vectors, getVectors(result));
        });
        assertThat(sstableRecall).isGreaterThanOrEqualTo(MIN_RECALL_AVG);
    }

    private double testMultiple(IntToDoubleFunction f)
    {
        int ITERS = 10;
        return IntStream.range(0, ITERS).mapToDouble(f).sum() / ITERS;
    }

    @Test
    public void testMultiSSTablesVectorSearch()
    {
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val")));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);
        // disable compaction
        String tableName = table;
        cluster.forEach(n -> n.runOnInstance(() -> {
            Keyspace keyspace = Keyspace.open(KEYSPACE);
            keyspace.getColumnFamilyStore(tableName).disableAutoCompaction();
        }));

        int vectorCountPerSSTable = getRandom().nextIntBetween(200, 500);
        int sstableCount = getRandom().nextIntBetween(3, 5);
        List<float[]> allVectors = new ArrayList<>(sstableCount * vectorCountPerSSTable);

        int pk = 0;
        for (int i = 0; i < sstableCount; i++)
        {
            List<float[]> vectors = generateVectors(vectorCountPerSSTable);
            for (float[] vector : vectors)
                execute("INSERT INTO %s (pk, val) VALUES (" + (pk++) + ", " + vectorString(vector) + " )");

            allVectors.addAll(vectors);
            cluster.forEach(n -> n.flush(KEYSPACE));
        }

        // query multiple sstable indexes in multiple node
        double recall = testMultiple((__) ->
        {
            int limit = Math.min(getRandom().nextIntBetween(50, 100), allVectors.size());
            float[] queryVector = randomVector();
            Object[][] result = searchWithLimit(queryVector, limit);
            return computeRecall(queryVector, allVectors, getVectors(result));
        });
        assertThat(recall).isGreaterThanOrEqualTo(MIN_RECALL_AVG);
    }

    @Test
    public void testBasicGeoDistance()
    {
        dimensionCount = 2;
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        // geo requries euclidean similarity function
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val") + " WITH OPTIONS = {'similarity_function' : 'euclidean'}"));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);
        // disable compaction
        String tableName = table;
        cluster.forEach(n -> n.runOnInstance(() -> {
            Keyspace keyspace = Keyspace.open(KEYSPACE);
            keyspace.getColumnFamilyStore(tableName).disableAutoCompaction();
        }));

        int vectorCountPerSSTable = getRandom().nextIntBetween(3000, 5000);
        int sstableCount = getRandom().nextIntBetween(7, 10);
        List<float[]> allVectors = new ArrayList<>(sstableCount * vectorCountPerSSTable);

        int pk = 0;
        for (int i = 0; i < sstableCount; i++)
        {
            List<float[]> vectors = generateUSBoundedGeoVectors(vectorCountPerSSTable);
            for (float[] vector : vectors)
                execute("INSERT INTO %s (pk, val) VALUES (" + (pk++) + ", " + vectorString(vector) + " )");

            allVectors.addAll(vectors);
            cluster.forEach(n -> n.flush(KEYSPACE));
        }

        // Run the query 50 times to get an average of several queries
        int queryCount = 50;
        double recallSum = 0;
        for (int i = 0; i < queryCount; i++)
        {
            // query multiple sstable indexes in multiple node
            int searchRadiusMeters = getRandom().nextIntBetween(500, 20000);
            float[] queryVector = randomUSVector();
            Object[][] result = execute("SELECT val FROM %s WHERE GEO_DISTANCE(val, " + Arrays.toString(queryVector) + ") < " + searchRadiusMeters);

            var recall = getGeoRecall(allVectors, queryVector, searchRadiusMeters, getVectors(result));
            recallSum += recall;
        }
        logger.info("Observed recall rate: {}", recallSum / queryCount);
        assertThat(recallSum / queryCount).isGreaterThanOrEqualTo(MIN_GEO_SEARCH_RECALL);
    }

    @Test
    public void testPartitionRestrictedVectorSearch()
    {
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val")));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);

        int vectorCount = getRandom().nextIntBetween(500, 1000);
        List<float[]> vectors = generateVectors(vectorCount);

        int pk = 0;
        for (float[] vector : vectors)
            execute("INSERT INTO %s (pk, val) VALUES (" + (pk++) + ", " + vectorString(vector) + " )");

        // query memtable index
        for (int executionCount = 0; executionCount < 50; executionCount++)
        {
            int key = getRandom().nextIntBetween(0, vectorCount - 1);
            float[] queryVector = randomVector();
            searchByKeyWithLimit(key, queryVector, 1, vectors);
        }

        cluster.forEach(n -> n.flush(KEYSPACE));

        // query on-disk index
        for (int executionCount = 0; executionCount < 50; executionCount++)
        {
            int key = getRandom().nextIntBetween(0, vectorCount - 1);
            float[] queryVector = randomVector();
            searchByKeyWithLimit(key, queryVector, 1, vectors);
        }
    }

    @Test
    public void rangeRestrictedTest()
    {
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val")));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);

        int vectorCount = getRandom().nextIntBetween(500, 1000);
        List<float[]> vectors = IntStream.range(0, vectorCount).mapToObj(s -> randomVector()).collect(Collectors.toList());

        int pk = 0;
        Multimap<Long, float[]> vectorsByToken = ArrayListMultimap.create();
        for (float[] vector : vectors)
        {
            vectorsByToken.put(Murmur3Partitioner.instance.getToken(Int32Type.instance.decompose(pk)).getLongValue(), vector);
            execute("INSERT INTO %s (pk, val) VALUES (" + (pk++) + ',' + vectorString(vector) + " )");
        }

        // query memtable index
        for (int executionCount = 0; executionCount < 50; executionCount++)
        {
            int key1 = getRandom().nextIntBetween(1, vectorCount * 2);
            long token1 = Murmur3Partitioner.instance.getToken(Int32Type.instance.decompose(key1)).getLongValue();
            int key2 = getRandom().nextIntBetween(1, vectorCount * 2);
            long token2 = Murmur3Partitioner.instance.getToken(Int32Type.instance.decompose(key2)).getLongValue();

            long minToken = Math.min(token1, token2);
            long maxToken = Math.max(token1, token2);
            float[] queryVector = randomVector();
            List<float[]> expected = vectorsByToken.entries().stream()
                                                   .filter(e -> e.getKey() >= minToken && e.getKey() <= maxToken)
                                                   .map(Map.Entry::getValue)
                                                   .sorted(Comparator.comparingDouble(v -> function.compare(vts.createFloatVector(v), vts.createFloatVector(queryVector))).reversed())
                                                   .collect(Collectors.toList());

            List<float[]> resultVectors = searchWithRange(queryVector, minToken, maxToken, expected.size());
            if (expected.isEmpty())
                assertThat(resultVectors).isEmpty();
            else
            {
                double recall = computeRecall(queryVector, resultVectors, expected);
                assertThat(recall).isGreaterThanOrEqualTo(0.8);
            }
        }

        cluster.forEach(n -> n.flush(KEYSPACE));

        // query on-disk index with existing key:
        for (int executionCount = 0; executionCount < 50; executionCount++)
        {
            int key1 = getRandom().nextIntBetween(1, vectorCount * 2);
            long token1 = Murmur3Partitioner.instance.getToken(Int32Type.instance.decompose(key1)).getLongValue();
            int key2 = getRandom().nextIntBetween(1, vectorCount * 2);
            long token2 = Murmur3Partitioner.instance.getToken(Int32Type.instance.decompose(key2)).getLongValue();

            long minToken = Math.min(token1, token2);
            long maxToken = Math.max(token1, token2);
            float[] queryVector = randomVector();
            List<float[]> expected = vectorsByToken.entries().stream()
                                                   .filter(e -> e.getKey() >= minToken && e.getKey() <= maxToken)
                                                   .map(Map.Entry::getValue)
                                                   .sorted(Comparator.comparingDouble(v -> function.compare(vts.createFloatVector(v), vts.createFloatVector(queryVector))).reversed())
                                                   .collect(Collectors.toList());

            List<float[]> resultVectors = searchWithRange(queryVector, minToken, maxToken, expected.size());
            if (expected.isEmpty())
                assertThat(resultVectors).isEmpty();
            else
            {
                double recall = computeRecall(queryVector, resultVectors, expected);
                assertThat(recall).isGreaterThanOrEqualTo(0.8);
            }
        }
    }

    @Test
    public void testInvalidVectorQueriesWithCosineSimilarity()
    {
        dimensionCount = 2;
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val") + " WITH OPTIONS = {'similarity_function' : 'cosine'}"));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);

        assertInvalidCosineOperations();
    }

    private static void assertInvalidCosineOperations()
    {
        assertThatThrownBy(() -> execute("INSERT INTO %s (pk, val) VALUES (0, [0.0, 0.0])")).hasMessage("Zero and near-zero vectors cannot be indexed or queried with cosine similarity");
        assertThatThrownBy(() -> execute("INSERT INTO %s (pk, val) VALUES (0, [1, NaN])")).hasMessage("non-finite value at vector[1]=NaN");
        assertThatThrownBy(() -> execute("INSERT INTO %s (pk, val) VALUES (0, [1, Infinity])")).hasMessage("non-finite value at vector[1]=Infinity");
        assertThatThrownBy(() -> execute("INSERT INTO %s (pk, val) VALUES (0, [-Infinity, 1])")).hasMessage("non-finite value at vector[0]=-Infinity");
        assertThatThrownBy(() -> execute("SELECT * FROM %s ORDER BY val ann of [0.0, 0.0] LIMIT 2")).hasMessage("Zero and near-zero vectors cannot be indexed or queried with cosine similarity");
        assertThatThrownBy(() -> execute("SELECT * FROM %s ORDER BY val ann of [1, NaN] LIMIT 2")).hasMessage("non-finite value at vector[1]=NaN");
        assertThatThrownBy(() -> execute("SELECT * FROM %s ORDER BY val ann of [1, Infinity] LIMIT 2")).hasMessage("non-finite value at vector[1]=Infinity");
        assertThatThrownBy(() -> execute("SELECT * FROM %s ORDER BY val ann of [-Infinity, 1] LIMIT 2")).hasMessage("non-finite value at vector[0]=-Infinity");
    }

    @Test
    public void testInvalidVectorQueriesWithDefaultSimilarity()
    {
        dimensionCount = 2;
        cluster.schemaChange(formatQuery(String.format(CREATE_TABLE, dimensionCount)));
        cluster.schemaChange(formatQuery(String.format(CREATE_INDEX, "val")));
        SAIUtil.waitForIndexQueryable(cluster, KEYSPACE);

        assertInvalidCosineOperations();
    }

    private List<float[]> searchWithRange(float[] queryVector, long minToken, long maxToken, int expectedSize)
    {
        Object[][] result = execute("SELECT val FROM %s WHERE token(pk) <= " + maxToken + " AND token(pk) >= " + minToken + " ORDER BY val ann of " + Arrays.toString(queryVector) + " LIMIT 1000");
        assertThat(result).hasNumberOfRows(expectedSize);
        return getVectors(result);
    }

    private Object[][] searchWithLimit(float[] queryVector, int limit)
    {
        Object[][] result = execute("SELECT val FROM %s ORDER BY val ann of " + Arrays.toString(queryVector) + " LIMIT " + limit);
        assertThat(result).hasNumberOfRows(limit);
        return result;
    }

    private Object[][] searchWithPageAndLimit(float[] queryVector, int pageSize, int limit)
    {
        // we don't know how many will be returned in case of paging, because coordinator resumes from last-returned-row's partiton
        return executeWithPaging("SELECT val FROM %s ORDER BY val ann of " + Arrays.toString(queryVector) + " LIMIT " + limit, pageSize);
    }

    private void searchByKeyWithLimit(int key, float[] queryVector, int limit, List<float[]> vectors)
    {
        Object[][] result = execute("SELECT val FROM %s WHERE pk = " + key + " ORDER BY val ann of " + Arrays.toString(queryVector) + " LIMIT " + limit);
        assertThat(result).hasNumberOfRows(1);
        float[] output = getVectors(result).get(0);
        assertThat(output).isEqualTo(vectors.get(key));
    }

    private static void assertDescendingScore(float[] queryVector, List<float[]> resultVectors)
    {
        float prevScore = -1;
        for (float[] current : resultVectors)
        {
            float score = function.compare(vts.createFloatVector(current), vts.createFloatVector(queryVector));
            if (prevScore >= 0)
                assertThat(score).isLessThanOrEqualTo(prevScore);

            prevScore = score;
        }
    }

    private static double computeRecall(float[] query, List<float[]> vectors, List<float[]> results)
    {
        assertDescendingScore(query, results);
        return VectorTester.computeRecall(vectors, query, results, function);
    }

    private List<float[]> generateVectors(int vectorCount)
    {
        return IntStream.range(0, vectorCount).mapToObj(s -> randomVector()).collect(Collectors.toList());
    }

    private List<float[]> getVectors(Object[][] result)
    {
        List<float[]> vectors = new ArrayList<>();

        // verify results are part of inserted vectors
        for (Object[] obj: result)
        {
            List<Float> list = (List<Float>) obj[0];
            float[] array = new float[list.size()];
            for (int index = 0; index < list.size(); index++)
                array[index] = list.get(index);
            vectors.add(array);
        }

        return vectors;
    }

    private String vectorString(float[] vector)
    {
        return Arrays.toString(vector);
    }

    private float[] randomVector()
    {
        return CQLTester.randomVector(dimensionCount);
    }

    private List<float[]> generateUSBoundedGeoVectors(int vectorCount)
    {
        return IntStream.range(0, vectorCount).mapToObj(s -> randomUSVector()).collect(Collectors.toList());
    }

    private float[] randomUSVector()
    {
        // Approximate bounding box for contiguous US locations
        var lat = getRandom().nextFloatBetween(24, 49);
        var lon = getRandom().nextFloatBetween(-124, -67);
        return new float[] {lat, lon};
    }

    private double getGeoRecall(List<float[]> allVectors, float[] query, float distance, List<float[]> resultVectors)
    {
        assertThat(allVectors).containsAll(resultVectors);
        var expectdVectors = allVectors.stream().filter(v -> GeoDistanceAccuracyTest.isWithinDistance(v, query, distance))
                                 .collect(Collectors.toSet());
        int matches = 0;
        for (float[] expectedVector : expectdVectors)
        {
            for (float[] resultVector : resultVectors)
            {
                if (Arrays.compare(expectedVector, resultVector) == 0)
                {
                    matches++;
                    break;
                }
            }
        }
        if (expectdVectors.isEmpty() && resultVectors.isEmpty())
            return 1.0;
        return matches * 1.0 / expectdVectors.size();
    }


    private static Object[][] execute(String query)
    {
        return execute(query, ConsistencyLevel.QUORUM);
    }

    private static Object[][] execute(String query, ConsistencyLevel consistencyLevel)
    {
        return cluster.coordinator(1).execute(formatQuery(query), consistencyLevel);
    }

    private static Object[][] executeWithPaging(String query, int pageSize)
    {
        Iterator<Object[]> iterator = cluster.coordinator(1).executeWithPaging(formatQuery(query), ConsistencyLevel.QUORUM, pageSize);
        List<Object[]> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);

        return list.toArray(new Object[0][]);
    }

    private static String formatQuery(String query)
    {
        return String.format(query, KEYSPACE + '.' + table);
    }
}
