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

package org.apache.cassandra.index.sai.cql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.index.sai.SAIUtil;
import org.apache.cassandra.index.sai.StorageAttachedIndex;
import org.apache.cassandra.index.sai.disk.format.Version;
import org.apache.cassandra.index.sai.disk.v1.SegmentBuilder;
import org.apache.cassandra.index.sai.disk.vector.CassandraOnHeapGraph;
import org.apache.cassandra.index.sai.disk.vector.VectorSourceModel;
import org.apache.cassandra.index.sai.plan.QueryController;
import org.apache.cassandra.inject.ActionBuilder;
import org.apache.cassandra.inject.Expression;
import org.apache.cassandra.inject.Injections;
import org.apache.cassandra.inject.InvokePointBuilder;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.tracing.TracingTestImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class VectorTypeTest extends VectorTester
{
    @Parameterized.Parameter
    public Version version;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data()
    {
        return Stream.of(Version.CA, Version.DC).map(v -> new Object[]{ v}).collect(Collectors.toList());
    }

    private static final IPartitioner partitioner = Murmur3Partitioner.instance;

    @BeforeClass
    public static void setupClass()
    {
        System.setProperty("cassandra.custom_tracing_class", "org.apache.cassandra.tracing.TracingTestImpl");
    }

    @Before
    @Override
    public void setup() throws Throwable
    {
        super.setup();
        SAIUtil.setLatestVersion(version);
    }

    @Override
    public void flush() {
        super.flush();
        verifyChecksum();
    }

    @Override
    public void compact() {
        super.compact();
        verifyChecksum();
    }

    @Test
    public void endToEndTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', [4.0, 5.0, 6.0])");

        UntypedResultSet result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);

        flush();
        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);

        execute("INSERT INTO %s (pk, str_val, val) VALUES (4, 'E', [5.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (5, 'F', [6.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (6, 'G', [7.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (7, 'H', [8.0, 5.0, 6.0])");

        flush();
        compact();

        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 5");
        assertThat(result).hasSize(5);

        // some data that only lives in memtable
        execute("INSERT INTO %s (pk, str_val, val) VALUES (8, 'I', [9.0, 5.0, 6.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (9, 'J', [10.0, 6.0, 7.0])");
        result = execute("SELECT * FROM %s ORDER BY val ann of [9.5, 5.5, 6.5] LIMIT 5");
        assertContainsInt(result, "pk", 8);
        assertContainsInt(result, "pk", 9);

        // data from sstables
        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertContainsInt(result, "pk", 1);
        assertContainsInt(result, "pk", 2);
    }

    @Test
    public void tracingTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', [4.0, 5.0, 6.0])");

        flush();

        execute("INSERT INTO %s (pk, str_val, val) VALUES (4, 'E', [5.0, 2.0, 3.0])");

        Tracing.instance.newSession(ClientState.forInternalCalls(), Tracing.TraceType.QUERY);
        execute("SELECT * FROM %s ORDER BY val ann of [9.5, 5.5, 6.5] LIMIT 5");
        for (String trace : ((TracingTestImpl) Tracing.instance).getTraces())
            assertThat(trace).doesNotContain("Executing single-partition query");
        // manual inspection to verify that no extra traces were included
        logger.info(((TracingTestImpl) Tracing.instance).getTraces().toString());

        // because we parameterized the test class we need to clean up after ourselves or the second run will fail
        Tracing.instance.stopSession();
    }

    @Test
    public void createIndexAfterInsertTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', [4.0, 5.0, 6.0])");

        flush();
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        UntypedResultSet result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);
    }

    public static void assertContainsInt(UntypedResultSet result, String columnName, int columnValue)
    {
        for (UntypedResultSet.Row row : result)
        {
            if (row.has(columnName))
            {
                int value = row.getInt(columnName);
                if (value == columnValue)
                {
                    return;
                }
            }
        }
        throw new AssertionError("Result set does not contain a row with " + columnName + " = " + columnValue);
    }

    @Test
    public void testTwoPredicates()
    {
        createTable("CREATE TABLE %s (pk int, b boolean, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, b, v) VALUES (0, true, [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (1, true, [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (2, false, [3.0, 4.0, 5.0])");

        // the vector given is closest to row 2, but we exclude that row because b=false
        var result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        // VSTODO assert specific row keys
        assertThat(result).hasSize(2);

        flush();
        compact();

        result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    public void testTwoPredicatesWithBruteForce()
    {
        // Note: the PKs in this test are chosen intentionally to ensure their tokens overlap so that
        // we can test the brute force path.
        setMaxBruteForceRows(0);
        createTable("CREATE TABLE %s (pk int, b boolean, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, b, v) VALUES (1, true, [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (2, true, [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (3, false, [3.0, 4.0, 5.0])");

        // the vector given is closest to row 2, but we exclude that row because b=false
        var result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        // VSTODO assert specific row keys
        assertThat(result).hasSize(2);

        flush();
        compact();

        result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(2);

        // Add 3 rows to memtable. Need number of rows to be greater than both maxBruteForceRows and the LIMIT
        execute("INSERT INTO %s (pk, b, v) VALUES (4, true, [4.0, 5.0, 6.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (5, true, [5.0, 6.0, 7.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (6, true, [6.0, 7.0, 8.0])");

        result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    public void testTwoPredicatesWithUnnecessaryAllowFiltering()
    {
        createTable("CREATE TABLE %s (pk int, b int, v vector<float, 3>, PRIMARY KEY(pk, b))");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, b, v) VALUES (0, 0, [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (1, 2, [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (2, 4, [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, b, v) VALUES (3, 6, [4.0, 5.0, 6.0])");

        // Choose a vector closer to b = 0 to ensure that b's restriction is applied.
        assertRows(execute("SELECT pk FROM %s WHERE b > 2 ORDER BY v ANN OF [1,2,3] LIMIT 2 ALLOW FILTERING;"),
                   row(2), row(3));
    }

    @Test
    public void testTwoPredicatesManyRows()
    {
        createTable("CREATE TABLE %s (pk int, b boolean, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");

        for (int i = 0; i < 100; i++)
            execute("INSERT INTO %s (pk, b, v) VALUES (?, true, ?)",
                    i, vector(i, i + 1, i + 2));

        var result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(2);

        flush();
        compact();

        result = execute("SELECT * FROM %s WHERE b=true ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    public void testThreePredicates()
    {
        createTable("CREATE TABLE %s (pk int, b boolean, v vector<float, 3>, str text, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(str) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, b, v, str) VALUES (0, true, [1.0, 2.0, 3.0], 'A')");
        execute("INSERT INTO %s (pk, b, v, str) VALUES (1, true, [2.0, 3.0, 4.0], 'B')");
        execute("INSERT INTO %s (pk, b, v, str) VALUES (2, false, [3.0, 4.0, 5.0], 'C')");

        // the vector given is closest to row 2, but we exclude that row because b=false and str!='B'
        var result = execute("SELECT * FROM %s WHERE b=true AND str='B' ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        // VSTODO assert specific row keys
        assertThat(result).hasSize(1);

        flush();
        compact();

        result = execute("SELECT * FROM %s WHERE b=true AND str='B' ORDER BY v ANN OF [3.1, 4.1, 5.1] LIMIT 2");
        assertThat(result).hasSize(1);
    }

    @Test
    public void testSameVectorMultipleRows()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'A', [1.0, 2.0, 3.0])");

        var result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);

        flush();
        compact();

        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);
    }

    @Test
    public void testQueryEmptyTable()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        var result = execute("SELECT * FROM %s ORDER BY val ANN OF [2.5, 3.5, 4.5] LIMIT 1");
        assertThat(result).hasSize(0);
    }

    @Test
    public void testQueryTableWithNulls()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', null)");
        var result = execute("SELECT * FROM %s ORDER BY val ANN OF [2.5, 3.5, 4.5] LIMIT 1");
        assertThat(result).hasSize(0);

        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [4.0, 5.0, 6.0])");
        result = execute("SELECT pk FROM %s ORDER BY val ANN OF [2.5, 3.5, 4.5] LIMIT 1");
        assertRows(result, row(1));
    }

    @Test
    public void testLimitLessThanInsertedRowCount()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        // Insert more rows than the query limit
        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [4.0, 5.0, 6.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [7.0, 8.0, 9.0])");

        // Query with limit less than inserted row count
        var result = execute("SELECT * FROM %s ORDER BY val ANN OF [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    public void testQueryMoreRowsThanInserted()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");

        var result = execute("SELECT * FROM %s ORDER BY val ANN OF [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(1);
    }

    @Test
    public void changingOptionsTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        if (CassandraRelevantProperties.SAI_HNSW_ALLOW_CUSTOM_PARAMETERS.getBoolean())
        {
            createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex' WITH OPTIONS = " +
                        "{'maximum_node_connections' : 10, 'construction_beam_width' : 200, 'similarity_function' : 'euclidean' }");
        }
        else
        {
            assertThatThrownBy(() -> createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex' WITH OPTIONS = " +
                                                 "{'maximum_node_connections' : 10, 'construction_beam_width' : 200, 'similarity_function' : 'euclidean' }"))
            .isInstanceOf(InvalidRequestException.class);
            return;
        }

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', [4.0, 5.0, 6.0])");

        UntypedResultSet result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);

        flush();
        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 3");
        assertThat(result).hasSize(3);

        execute("INSERT INTO %s (pk, str_val, val) VALUES (4, 'E', [5.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (5, 'F', [6.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (6, 'G', [7.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (7, 'H', [8.0, 5.0, 6.0])");

        flush();
        compact();

        result = execute("SELECT * FROM %s ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 5");
        assertThat(result).hasSize(5);
    }

    @Test
    public void defaultOptionsTest()
    {
        createTable("CREATE TABLE %s (pk int, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");

        var sim = getCurrentColumnFamilyStore().indexManager;
        var index = (StorageAttachedIndex) sim.listIndexes().iterator().next();
        assertEquals(VectorSourceModel.OTHER, index.getIndexContext().getIndexWriterConfig().getSourceModel());
        assertEquals(VectorSimilarityFunction.COSINE, index.getIndexContext().getIndexWriterConfig().getSimilarityFunction());
    }

    @Test
    public void customModelOptionsTest()
    {
        createTable("CREATE TABLE %s (pk int, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex' WITH OPTIONS = {'source_model' : 'ada002' }");

        var sim = getCurrentColumnFamilyStore().indexManager;
        var index = (StorageAttachedIndex) sim.listIndexes().iterator().next();
        assertEquals(VectorSourceModel.ADA002, index.getIndexContext().getIndexWriterConfig().getSourceModel());
        assertEquals(VectorSimilarityFunction.DOT_PRODUCT, index.getIndexContext().getIndexWriterConfig().getSimilarityFunction());
    }

    @Test
    public void obsoleteOptionsTest()
    {
        createTable("CREATE TABLE %s (pk int, v vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex' WITH OPTIONS = {'optimize_for' : 'recall' }");
        // as long as CREATE doesn't error out, we're good
    }

    @Test
    public void bindVariablesTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', ?)", vector(1, 2 , 3));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', ?)", vector(2 , 3, 4));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', ?)", vector(3, 4, 5));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', ?)", vector(4, 5, 6));

        UntypedResultSet result = execute("SELECT * FROM %s ORDER BY val ann of ? LIMIT 3", vector(2.5f, 3.5f, 4.5f));
        assertThat(result).hasSize(3);
    }

    @Test
    public void intersectedSearcherTest()
    {
        // check that we correctly get back the two rows with str_val=B even when those are not
        // the closest rows to the query vector
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(str_val) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', ?)", vector(1, 2 , 3));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', ?)", vector(2 , 3, 4));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', ?)", vector(3, 4, 5));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'B', ?)", vector(4, 5, 6));
        execute("INSERT INTO %s (pk, str_val, val) VALUES (4, 'E', ?)", vector(5, 6, 7));

        UntypedResultSet result = execute("SELECT * FROM %s WHERE str_val = 'B' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(2);

        flush();
        result = execute("SELECT * FROM %s WHERE str_val = 'B' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    public void nullVectorTest()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(str_val) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', ?)", vector(1, 2 , 3));
        execute("INSERT INTO %s (pk, str_val) VALUES (1, 'B')"); // no vector
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', ?)", vector(3, 4, 5));
        execute("INSERT INTO %s (pk, str_val) VALUES (3, 'D')"); // no vector
        execute("INSERT INTO %s (pk, str_val, val) VALUES (4, 'E', ?)", vector(5, 6, 7));

        UntypedResultSet result = execute("SELECT * FROM %s WHERE str_val = 'B' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(0);

        result = execute("SELECT * FROM %s WHERE str_val = 'A' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(1);

        flush();

        result = execute("SELECT * FROM %s WHERE str_val = 'B' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(0);

        result = execute("SELECT * FROM %s WHERE str_val = 'A' ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2");
        assertThat(result).hasSize(1);
    }

    @Test
    public void lwtTest()
    {
        createTable("CREATE TABLE %s (p int, c int, v text, vec vector<float, 2>, PRIMARY KEY(p, c))");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (p, c, v) VALUES (?, ?, ?)", 0, 0, "test");
        execute("INSERT INTO %s (p, c, v) VALUES (?, ?, ?)", 0, 1, "00112233445566");

        execute("UPDATE %s SET v='00112233', vec=[0.9, 0.7] WHERE p = 0 AND c = 0 IF v = 'test'");

        UntypedResultSet result = execute("SELECT * FROM %s ORDER BY vec ANN OF [0.1, 0.9] LIMIT 100");

        assertThat(result).hasSize(1);
    }

    @Test
    public void twoVectorFieldsTest()
    {
        createTable("CREATE TABLE %s (pk int, v2 vector<float, 2>, v3 vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(v2) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(v3) USING 'StorageAttachedIndex'");
    }

    @Test
    public void primaryKeySearchTest()
    {
        createTable("CREATE TABLE %s (pk int, val vector<float, 3>, i int, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        var N = 5;
        for (int i = 0; i < N; i++)
            execute("INSERT INTO %s (pk, val) VALUES (?, ?)", i, vector(1 + i, 2 + i, 3 + i));

        for (int i = 0; i < N; i++)
        {
            UntypedResultSet result = execute("SELECT pk FROM %s WHERE pk = ? ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2", i);
            assertThat(result).hasSize(1);
            assertRows(result, row(i));
        }

        flush();
        for (int i = 0; i < N; i++)
        {
            UntypedResultSet result = execute("SELECT pk FROM %s WHERE pk = ? ORDER BY val ann of [2.5, 3.5, 4.5] LIMIT 2", i);
            assertThat(result).hasSize(1);
            assertRows(result, row(i));
        }
    }

    @Test
    public void partitionKeySearchTest()
    {
        createTable("CREATE TABLE %s (partition int, row int, val vector<float, 2>, PRIMARY KEY(partition, row))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function' : 'euclidean'}");

        var nPartitions = 5;
        var rowsPerPartition = 10;
        Map<Integer, List<float[]>> vectorsByPartition = new HashMap<>();

        for (int i = 1; i <= nPartitions; i++)
        {
            for (int j = 1; j <= rowsPerPartition; j++)
            {
                logger.debug("Inserting partition {} row {}: [{}, {}]", i, j, i, j);
                execute("INSERT INTO %s (partition, row, val) VALUES (?, ?, ?)", i, j, vector((float) i, (float) j));
                float[] vector = {(float) i, (float) j};
                vectorsByPartition.computeIfAbsent(i, k -> new ArrayList<>()).add(vector);
            }
        }

        var queryVector = vector(1.5f, 1.5f);
        for (int i = 1; i <= nPartitions; i++)
        {
            UntypedResultSet result = execute("SELECT partition, row FROM %s WHERE partition = ? ORDER BY val ann of ? LIMIT 2", i, queryVector);
            assertThat(result).hasSize(2);
            assertRowsIgnoringOrder(result,
                                    row(i, 1),
                                    row(i, 2));
        }

        flush();
        for (int i = 1; i <= nPartitions; i++)
        {
            UntypedResultSet result = execute("SELECT partition, row FROM %s WHERE partition = ? ORDER BY val ann of ? LIMIT 2", i, queryVector);
            assertThat(result).hasSize(2);
            assertRowsIgnoringOrder(result,
                                    row(i, 1),
                                    row(i, 2));
        }
    }

    @Test
    public void rangeSearchTest() throws Throwable
    {
        createTable("CREATE TABLE %s (partition int, val vector<float, 2>, PRIMARY KEY(partition))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function' : 'euclidean'}");

        var nPartitions = 100;
        Map<Integer, float[]> vectorsByKey = new HashMap<>();

        for (int i = 1; i <= nPartitions; i++)
        {
            float[] vector = {(float) i, (float) i};
            execute("INSERT INTO %s (partition, val) VALUES (?, ?)", i, vector(vector));
            vectorsByKey.put(i, vector);
        }

        var queryVector = vector(1.5f, 1.5f);
        CheckedFunction tester = () -> {
            for (int i = 1; i <= nPartitions; i++)
            {
                UntypedResultSet result = execute("SELECT partition FROM %s WHERE token(partition) > token(?) ORDER BY val ann of ? LIMIT 1000", i, queryVector);
                assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysWithLowerBound(vectorsByKey.keySet(), i, false));

                result = execute("SELECT partition FROM %s WHERE token(partition) >= token(?) ORDER BY val ann of ? LIMIT 1000", i, queryVector);
                assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysWithLowerBound(vectorsByKey.keySet(), i, true));

                result = execute("SELECT partition FROM %s WHERE token(partition) < token(?) ORDER BY val ann of ? LIMIT 1000", i, queryVector);
                assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysWithUpperBound(vectorsByKey.keySet(), i, false));

                result = execute("SELECT partition FROM %s WHERE token(partition) <= token(?) ORDER BY val ann of ? LIMIT 1000", i, queryVector);
                assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysWithUpperBound(vectorsByKey.keySet(), i, true));

                for (int j = 1; j <= nPartitions; j++)
                {
                    result = execute("SELECT partition FROM %s WHERE token(partition) >= token(?) AND token(partition) <= token(?) ORDER BY val ann of ? LIMIT 1000", i, j, queryVector);
                    assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysInBounds(vectorsByKey.keySet(), i, true, j, true));

                    result = execute("SELECT partition FROM %s WHERE token(partition) > token(?) AND token(partition) <= token(?) ORDER BY val ann of ? LIMIT 1000", i, j, queryVector);
                    assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysInBounds(vectorsByKey.keySet(), i, false, j, true));

                    result = execute("SELECT partition FROM %s WHERE token(partition) >= token(?) AND token(partition) < token(?) ORDER BY val ann of ? LIMIT 1000", i, j, queryVector);
                    assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysInBounds(vectorsByKey.keySet(), i, true, j, false));

                    result = execute("SELECT partition FROM %s WHERE token(partition) > token(?) AND token(partition) < token(?) ORDER BY val ann of ? LIMIT 1000", i, j, queryVector);
                    assertThat(keys(result)).containsExactlyInAnyOrderElementsOf(keysInBounds(vectorsByKey.keySet(), i, false, j, false));
                }
            }
        };

        tester.apply();

        flush();

        tester.apply();
    }

    private Collection<Integer> keys(UntypedResultSet result)
    {
        List<Integer> keys = new ArrayList<>(result.size());
        for (UntypedResultSet.Row row : result)
            keys.add(row.getInt("partition"));
        return keys;
    }

    private Collection<Integer> keysWithLowerBound(Collection<Integer> keys, int leftKey, boolean leftInclusive)
    {
        return keysInTokenRange(keys, partitioner.getToken(Int32Type.instance.decompose(leftKey)), leftInclusive,
                                partitioner.getMaximumToken().getToken(), true);
    }

    private Collection<Integer> keysWithUpperBound(Collection<Integer> keys, int rightKey, boolean rightInclusive)
    {
        return keysInTokenRange(keys, partitioner.getMinimumToken().getToken(), true,
                                partitioner.getToken(Int32Type.instance.decompose(rightKey)), rightInclusive);
    }

    private Collection<Integer> keysInBounds(Collection<Integer> keys, int leftKey, boolean leftInclusive, int rightKey, boolean rightInclusive)
    {
        return keysInTokenRange(keys, partitioner.getToken(Int32Type.instance.decompose(leftKey)), leftInclusive,
                                partitioner.getToken(Int32Type.instance.decompose(rightKey)), rightInclusive);
    }

    private Collection<Integer> keysInTokenRange(Collection<Integer> keys, Token leftToken, boolean leftInclusive, Token rightToken, boolean rightInclusive)
    {
        long left = leftToken.getLongValue();
        long right = rightToken.getLongValue();
        return keys.stream()
               .filter(k -> {
                   long t = partitioner.getToken(Int32Type.instance.decompose(k)).getLongValue();
                   return (left < t || left == t && leftInclusive) && (t < right || t == right && rightInclusive);
               }).collect(Collectors.toSet());
    }

    @Test
    public void selectFloatVectorFunctions()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int primary key, value vector<float, 2>)");

        // basic functionality
        Vector<Float> q = vector(1f, 2f);
        execute("INSERT INTO %s (pk, value) VALUES (0, ?)", vector(1, 2));
        execute("SELECT similarity_cosine(value, value) FROM %s WHERE pk=0");

        // type inference checks
        var result = execute("SELECT similarity_cosine(value, ?) FROM %s WHERe pk=0", q);
        assertRows(result, row(1f));
        result = execute("SELECT similarity_euclidean(value, ?) FROM %s WHERe pk=0", q);
        assertRows(result, row(1f));
        execute("SELECT similarity_cosine(?, value) FROM %s WHERE pk=0", q);
        assertThatThrownBy(() -> execute("SELECT similarity_cosine(?, ?) FROM %s WHERE pk=0", q, q))
        .hasMessageContaining("Cannot infer type of argument ?");

        // with explicit typing
        execute("SELECT similarity_cosine((vector<float, 2>) ?, ?) FROM %s WHERE pk=0", q, q);
        execute("SELECT similarity_cosine(?, (vector<float, 2>) ?) FROM %s WHERE pk=0", q, q);
        execute("SELECT similarity_cosine((vector<float, 2>) ?, (vector<float, 2>) ?) FROM %s WHERE pk=0", q, q);
    }

    @Test
    public void selectSimilarityWithAnn()
    {
        createTable("CREATE TABLE %s (pk int, str_val text, val vector<float, 3>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        execute("INSERT INTO %s (pk, str_val, val) VALUES (0, 'A', [1.0, 2.0, 3.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (1, 'B', [2.0, 3.0, 4.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (2, 'C', [3.0, 4.0, 5.0])");
        execute("INSERT INTO %s (pk, str_val, val) VALUES (3, 'D', [4.0, 5.0, 6.0])");

        Vector<Float> q = vector(1.5f, 2.5f, 3.5f);
        var result = execute("SELECT str_val, similarity_cosine(val, ?) FROM %s ORDER BY val ANN OF ? LIMIT 2",
                q, q);

        assertRowsIgnoringOrder(result,
                row("A", 0.9987074f),
                row("B", 0.9993764f));
    }

    @Test
    public void castedTerminalFloatVectorFunctions()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int primary key, value vector<float, 2>)");

        execute("INSERT INTO %s (pk, value) VALUES (0, ?)", vector(1, 2));
        execute("SELECT similarity_cosine(value, (vector<float, 2>) [1.0, 1.0]) FROM %s WHERE pk=0");
        execute("SELECT similarity_cosine((vector<float, 2>) [1.0, 1.0], value) FROM %s WHERE pk=0");
        execute("SELECT similarity_cosine((vector<float, 2>) [1.0, 1.0], (vector<float, 2>) [1.0, 1.0]) FROM %s WHERE pk=0");
    }

    @Test
    public void inferredTerminalFloatVectorFunctions()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int primary key, value vector<float, 2>)");

        execute("INSERT INTO %s (pk, value) VALUES (0, ?)", vector(1, 2));
        assertRows(execute("SELECT similarity_cosine(value, [2.0, 4.0]) FROM %s WHERE pk=0"), row(1f));
        assertRows(execute("SELECT similarity_cosine([2.0, 4.0], value) FROM %s WHERE pk=0"), row(1f));
        assertRows(execute("SELECT similarity_cosine([1.0, 2.0], [2.0, 4.0]) FROM %s WHERE pk=0"), row(1f));

        // wrong number of arguments
        assertInvalidMessage("Invalid number of arguments for function system.similarity_cosine(vector<float, n>, vector<float, n>)",
                             "SELECT similarity_cosine([1.0, 2.0]) FROM %s WHERE pk=0");
        assertInvalidMessage("Invalid number of arguments for function system.similarity_cosine(vector<float, n>, vector<float, n>)",
                             "SELECT similarity_cosine([1.0, 2.0]) FROM %s WHERE pk=0");

        // assignable element types
        assertRows(execute("SELECT similarity_cosine([1, 2], [2, 4]) FROM %s WHERE pk=0"), row(1f));
        assertRows(execute("SELECT similarity_cosine([1.0, 2.0], [2, 4]) FROM %s WHERE pk=0"), row(1f));
        assertRows(execute("SELECT similarity_cosine([1, 2], [2.0, 4.0]) FROM %s WHERE pk=0"), row(1f));

        // not-assignable element types
        assertInvalidMessage("Type error: ['a', 'b'] cannot be passed as argument 1",
                             "SELECT similarity_cosine(value, ['a', 'b']) FROM %s WHERE pk=0");
        assertInvalidMessage("Type error: ['a', 'b'] cannot be passed as argument 0",
                             "SELECT similarity_cosine(['a', 'b'], value) FROM %s WHERE pk=0");
        assertInvalidMessage("Type error: ['a', 'b'] cannot be passed as argument 0",
                             "SELECT similarity_cosine(['a', 'b'], ['a', 'b']) FROM %s WHERE pk=0");

        // different vector sizes, message could be more informative
        assertInvalidMessage("All arguments must have the same vector dimensions",
                             "SELECT similarity_cosine(value, [2, 4, 6]) FROM %s WHERE pk=0");
        assertInvalidMessage("All arguments must have the same vector dimensions",
                             "SELECT similarity_cosine([1, 2, 3], value) FROM %s WHERE pk=0");
        assertInvalidMessage("All arguments must have the same vector dimensions",
                             "SELECT similarity_cosine([1, 2], [3, 4, 5]) FROM %s WHERE pk=0");
    }

    @Test
    public void testSamePKWithBruteForceAndGraphBasedScoring()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int, vec vector<float, 2>, PRIMARY KEY(pk))");
        // Use euclidean distance to more easily verify correctness of caching
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex' WITH OPTIONS = { 'similarity_function' : 'euclidean' }");

        // Put one row in the first ss table to guarantee brute force method. This vector is also the most similar.
        execute("INSERT INTO %s (pk, vec) VALUES (?, ?)", 10, vector(1f, 1f));
        flush();

        // Must be enough rows to go to graph
        for (int j = 1; j <= 10; j++)
        {
            execute("INSERT INTO %s (pk, vec) VALUES (?, ?)", j, vector(j, j));
        }
        flush();

        assertRows(execute("SELECT pk FROM %s ORDER BY vec ANN OF [1,1] LIMIT 2"), row(1), row(2));
    }

    @Test
    public void testSamePKWithBruteForceAndOnDiskGraphBasedScoring()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int, vec vector<float, 2>, PRIMARY KEY(pk))");
        // Use euclidean distance to more easily verify correctness of caching
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex' WITH OPTIONS = { 'similarity_function' : 'euclidean' }");

        // Put one row in the first ss table to guarantee brute force method. This vector is also the most similar.
        execute("INSERT INTO %s (pk, vec) VALUES (?, ?)", 10, vector(1f, 1f));
        flush();

        // over 1024 vectors to guarantee PQ on disk
        // Must be enough rows to go to graph
        for (int j = 1; j <= 1100; j++)
        {
            execute("INSERT INTO %s (pk, vec) VALUES (?, ?)", j, vector((float) j, (float) j));
        }
        flush();

        assertRows(execute("SELECT pk FROM %s ORDER BY vec ANN OF [1,1] LIMIT 2"), row(1), row(2));
    }

    @Test
    public void testRowWithMissingVectorThatMatchesQueryPredicates()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int, val text, vec vector<float, 2>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        // There was an edge case where we failed because there was just a single row in the table.
        execute("INSERT INTO %s (pk, val) VALUES (1, 'match me')");
        assertRows(execute("SELECT pk FROM %s WHERE val = 'match me' ORDER BY vec ANN OF [1,1] LIMIT 2"));
        // Push memtable to sstable. we should get same result
        flush();
        assertRows(execute("SELECT pk FROM %s WHERE val = 'match me' ORDER BY vec ANN OF [1,1] LIMIT 2"));
    }

    @Test
    public void testMultipleVectorsInMemoryWithPredicate()
    {
        createTable(KEYSPACE, "CREATE TABLE %s (pk int, val text, vec vector<float, 2>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        // When we search the memtable, we filter out PKs outside the memtable's bounrdaries.
        // Persist two rows and push to sstable that will be outside of bounds.
        execute("INSERT INTO %s (pk, val, vec) VALUES (1, 'match me', [1, 1])");
        execute("INSERT INTO %s (pk, val, vec) VALUES (5, 'match me', [1, 1])");
        flush();
        execute("INSERT INTO %s (pk, val, vec) VALUES (2, 'match me', [1, 1])");
        execute("INSERT INTO %s (pk, val, vec) VALUES (3, 'match me', [1, 1])");
        execute("INSERT INTO %s (pk, val, vec) VALUES (4, 'match me', [1, 1])");
        assertRowsIgnoringOrder(execute("SELECT pk FROM %s WHERE val = 'match me' ORDER BY vec ANN OF [1,1] LIMIT 5"),
                                row(1), row(2), row(3), row(4), row(5));
    }

    @Test
    public void testNestedANNQuery()
    {
        createTable("CREATE TABLE %s (pk int, name text, body text, vals vector<float, 2>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(vals) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(name) USING 'StorageAttachedIndex'");
        execute("INSERT INTO %s (pk, name, body, vals) VALUES (1, 'Ann', 'A lizard said bad things to the snakes', [0.1, 0.1])");
        execute("INSERT INTO %s (pk, name, body, vals) VALUES (2, 'Bea', 'Please wear protective gear before operating the machine', [0.2, -0.3])");
        execute("INSERT INTO %s (pk, name, body, vals) VALUES (3, 'Cal', 'My name is Slim Shady', [0.0, 0.9])");
        execute("INSERT INTO %s (pk, name, body, vals) VALUES (4, 'Bea', 'I repeat: wear your helmet!', [0.3, -0.2])");
        var result = execute("SELECT pk FROM %s WHERE name='Bea' OR name='Ann' ORDER BY vals ANN OF [0.3, 0.1] LIMIT 5");
        assertRowsIgnoringOrder(result, row(1), row(2), row(4));
    }

    @Test
    public void testIntersectionWithMatchingPrimaryKeyDownToClusteringValues() throws Throwable
    {
        createTable("CREATE TABLE %s (pk int, a int, b int, c int, vec vector<float, 2>, PRIMARY KEY(pk, a))");
        createIndex("CREATE CUSTOM INDEX ON %s(b) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(c) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");

        // This row is created so that it matches the query parameters, and so that the PK is before the other PKs.
        // The token for 5 is -7509452495886106294 and the token for 1 is -4069959284402364209.
        execute("INSERT INTO %s (pk, a, b, c, vec) VALUES (?, ?, ?, ?, ?)", 5, 1, 1, 2, vector(1, 1));
        // This row is created so that it matches one, but not both, predicates, and so that it has the same token
        // as the third row, but is technically before it when sorting using clustering columns.
        execute("INSERT INTO %s (pk, a, b, c, vec) VALUES (?, ?, ?, ?, ?)", 1, 1, 1, 0, vector(1, 1));
        // This row is the only valid match and is the final row in the sstable.
        execute("INSERT INTO %s (pk, a, b, c, vec) VALUES (?, ?, ?, ?, ?)", 1, 2, 1, 2, vector(1, 1));

        beforeAndAfterFlush(
        () -> {
            // Query has three important details. First, we restrict by the partition, then we have an intersection
            // on b and c. It is a vector query because there is a separate code path for it.
            assertRows(execute("SELECT a FROM %s WHERE b = 1 AND c = 2 AND pk = 1 ORDER BY vec ANN OF [1,1] LIMIT 3"), row(2));
            // Verify this works for the non vector code path as well, which was also broken.
            assertRows(execute("SELECT a FROM %s WHERE b = 1 AND c = 2 AND pk = 1"), row(2));
        });
    }

    // search across multiple sstables each with multiple segments, verify results with and without non-ann filtering
    @Test
    public void multipleSSTablesAndMultipleSegmentsTest()
    {
        createTable("CREATE TABLE %s (pk int, constant boolean, val vector<float, 2>, PRIMARY KEY(pk))");
        disableCompaction(KEYSPACE);

        int vectorCountPerSSTable = getRandom().nextIntBetween(200, 400);
        int pk = 0;

        // 50 rows per segment to ensure certain kinds of skipping
        SegmentBuilder.updateLastValidSegmentRowId(50);

        // create multiple sstables to ensure that not all PKs have the same source table
        for (int i = 0; i < 6; i++)
        {
            for (int row = 0; row < vectorCountPerSSTable; row++)
                // Create random vectors, we're only testing internal consistency
                execute("INSERT INTO %s (pk, constant, val) VALUES (?, ?, ?)", pk++, true,
                        vector(getRandom().nextIntBetween(1, 400), getRandom().nextIntBetween(1, 400)));
            flush();
        }

        // create indexes on existing sstable to produce multiple segments
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function' : 'euclidean'}");
        createIndex("CREATE CUSTOM INDEX ON %s(constant) USING 'StorageAttachedIndex'");

        // query multiple on-disk indexes
        int limit = getRandom().nextIntBetween(5,25);
        // Pick a vector in the middle of the distribution
        UntypedResultSet unfilteredResults = execute("SELECT pk FROM %s ORDER BY val ANN OF [200,200] LIMIT ?", limit);
        UntypedResultSet filteredResults = execute("SELECT pk FROM %s WHERE constant = true ORDER BY val ANN OF [200,200] LIMIT ?", limit);

        // Extract the primary keys while retaining order
        var unfilteredRows = unfilteredResults.stream().map(row -> row.getInt("pk")).toArray();
        var filteredRows = filteredResults.stream().map(row -> row.getInt("pk")).toArray();

        // Assert that the results are the same
        assertThat(filteredRows).containsExactly(unfilteredRows);
    }

    @Test
    public void insertstuff()
    {
        // This test requires the non-bruteforce route
        setMaxBruteForceRows(0);
        createTable("CREATE TABLE %s (pk int, val text, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(val) USING 'StorageAttachedIndex'");

        // Insert data
        execute("INSERT INTO %s (pk, val) VALUES (1, 'A')");
        execute("INSERT INTO %s (pk, val) VALUES (2, 'B')");
        execute("INSERT INTO %s (pk, val) VALUES (3, 'C')");

        // no solution yet, so flush()
        flush();

        // query with order
        assertRows(execute("SELECT pk FROM %s ORDER BY val limit 3"), row(1), row(2), row(3));
        assertRows(execute("SELECT pk FROM %s ORDER BY val limit 1"), row(1));
    }

    @Test
    public void testEnsureIndexQueryableAfterTransientFailure() throws Throwable
    {
        createTable("CREATE TABLE %s (pk int, vec vector<float, 2>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");

        var injection = Injections.newCustom("fail_on_searcher_search")
                                  .add(InvokePointBuilder.newInvokePoint().onClass(GraphSearcher.class).onMethod("search").atEntry())
                                  .add(ActionBuilder.newActionBuilder().actions().doThrow(RuntimeException.class, Expression.quote("Injected failure!")))
                                  .build();
        Injections.inject(injection);
        // Insert data so we can query the index
        execute("INSERT INTO %s (pk, vec) VALUES (1, [1,1])");

        // Ensure that we fail, as expected, and that a subsequent call to search is successful.
        beforeAndAfterFlush(() -> {
            injection.enable();
            assertThatThrownBy(() -> execute("SELECT pk FROM %s ORDER BY vec ANN OF [1,1] LIMIT 2")).hasMessageContaining("Injected failure!");
            injection.disable();
            assertRows(execute("SELECT pk FROM %s ORDER BY vec ANN OF [1,1] LIMIT 2"), row(1));
        });
    }

    @Test
    public void testCompactionWithEnoughRowsForPQAndDeleteARow()
    {
        createTable("CREATE TABLE %s (pk int, vec vector<float, 2>, PRIMARY KEY(pk))");
        createIndex("CREATE CUSTOM INDEX ON %s(vec) USING 'StorageAttachedIndex'");

        disableCompaction();

        for (int i = 0; i <= CassandraOnHeapGraph.MIN_PQ_ROWS; i++)
            execute("INSERT INTO %s (pk, vec) VALUES (?, ?)", i, vector(i, i + 1));
        flush();

        // By deleting a row, we trigger a key histogram to round its estimate to 0 instead of 1 rows per key, and
        // that broke compaction, so we test that here.
        execute("DELETE FROM %s WHERE pk = 0");
        flush();

        // Run compaction, it fails if compaction is not successful
        compact();

        // Confirm we can query the data
        assertRowCount(execute("SELECT * FROM %s ORDER BY vec ANN OF [1,2] LIMIT 1"), 1);
    }

    /**
     * Tests a filter-then-sort query with a concurrent vector deletion. See CNDB-10536 for details.
     */
    @Test
    public void testFilterThenSortQueryWithConcurrentVectorDeletion() throws Throwable
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, v vector<float, 2>, c int)");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex'");
        createIndex("CREATE CUSTOM INDEX ON %s(c) USING 'StorageAttachedIndex'");

        // write into memtable
        execute("INSERT INTO %s (k, v, c) VALUES (1, [1, 1], 1)");
        execute("INSERT INTO %s (k, v, c) VALUES (2, [2, 2], 1)");

        // inject a barrier to block CassandraOnHeapGraph#getOrdinal
        Injections.Barrier barrier = Injections.newBarrier("block_get_ordinal", 2, false)
                                               .add(InvokePointBuilder.newInvokePoint()
                                                                      .onClass(CassandraOnHeapGraph.class)
                                                                      .onMethod("getOrdinal")
                                                                      .atEntry())
                                               .build();
        Injections.inject(barrier);

        // start a filter-then-sort query asynchronously that will get blocked in the injected barrier
        QueryController.QUERY_OPT_LEVEL = 0;
        try
        {
            ExecutorService executor = Executors.newFixedThreadPool(1);
            String select = "SELECT k FROM %s WHERE c=1 ORDER BY v ANN OF [1, 1] LIMIT 100";
            Future<UntypedResultSet> future = executor.submit(() -> execute(select));

            // once the query is blocked, delete one of the vectors and flush, so the postings for the vector are removed
            waitForAssert(() -> Assert.assertEquals(1, barrier.getCount()));
            execute("DELETE v FROM %s WHERE k = 1");
            flush();

            // release the barrier to resume the query, which should succeed
            barrier.countDown();
            assertRows(future.get(), row(2));

            assertEquals(0, executor.shutdownNow().size());
        }
        finally
        {
            QueryController.QUERY_OPT_LEVEL = 1;
        }
    }

    @Test
    public void testPartitionKeyRestrictionCombinedWithSearchPredicate() throws Throwable
    {
        // Need to test the search then order path
        QueryController.QUERY_OPT_LEVEL = 0;

        // We use a clustered primary key to simplify the mental model for this test.
        // The bug this test exposed happens when the last row(s) in a segment, based on PK order, are present
        // in a peer index for an sstable's search index but not its vector index.
        createTable("CREATE TABLE %s (partition int, i int, v vector<float, 2>, c int, PRIMARY KEY(partition, i))");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'euclidean'}");
        createIndex("CREATE CUSTOM INDEX ON %s(c) USING 'StorageAttachedIndex'");

        var partitionKeys = new ArrayList<Integer>();
        // Insert many rows
        for (int i = 1; i < 1000; i++)
        {
            execute("INSERT INTO %s (partition, i, v, c) VALUES (?, ?, ?, ?)", i, i, vector(i, i), i);
            partitionKeys.add(i);
        }

        beforeAndAfterFlush(() -> {
            // Restricted by partition key and with low as well as high cardinality of results for column c
            assertRows(execute("SELECT i FROM %s WHERE partition = 1 AND c > 0 ORDER BY v ANN OF [1,1] LIMIT 1"), row(1));
            assertRows(execute("SELECT i FROM %s WHERE partition = 1 AND c < 10 ORDER BY v ANN OF [1,1] LIMIT 1"), row(1));

            // Do some partition key range queries, the restriction on c is meaningless, but forces the search then
            // order path
            var r1 = execute("SELECT partition FROM %s WHERE token(partition) < token(11) AND c > 0 ORDER BY v ANN OF [1,1] LIMIT 1000");
            var e1 = keysWithUpperBound(partitionKeys, 11,false);
            assertThat(keys(r1)).containsExactlyInAnyOrderElementsOf(e1);

            var r2 = execute("SELECT partition FROM %s WHERE token(partition) >= token(11) AND token(partition) <= token(20) AND c <= 1000 ORDER BY v ANN OF [1,1] LIMIT 1000");
            var e2 = keysInBounds(partitionKeys, 11, true, 20, true);
            assertThat(keys(r2)).containsExactlyInAnyOrderElementsOf(e2);
        });
    }
}
