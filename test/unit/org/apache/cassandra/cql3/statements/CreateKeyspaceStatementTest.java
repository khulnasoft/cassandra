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

package org.apache.cassandra.cql3.statements;

import org.junit.Test;

import com.khulnasoft.driver.core.ResultSet;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.schema.CreateKeyspaceStatement;
import org.apache.cassandra.service.ClientState;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class CreateKeyspaceStatementTest extends CQLTester
{
    @Test
    public void testAttributeOverride()
    {
        String keyspaceName = createKeyspaceName();
        CQLStatement.Raw raw = QueryProcessor.parseStatement(String.format("CREATE KEYSPACE %s WITH REPLICATION = " +
                                                                           "{ 'class' : 'SimpleStrategy', 'replication_factor' : '1' }",
                                                                           keyspaceName));

        CQLStatement stm = raw.prepare(ClientState.forInternalCalls());
        ((CreateKeyspaceStatement) stm).overrideAttribute("replication_factor", "replication_factor", "2");
        assertEquals("2", ((CreateKeyspaceStatement) stm).getAttribute("replication_factor"));
    }

    @Test
    public void ignoreUnsupportedGraphEngineProperty() throws Throwable
    {
        String keyspaceName = createKeyspaceName();
        String keyspaceOptions = "graph_engine = 'Core'";

        // should not throw
        ResultSet rows = executeNet(String.format("CREATE KEYSPACE %s WITH REPLICATION = " +
                                                  "{ 'class' : 'SimpleStrategy', 'replication_factor' : '1' } AND %s",
                                                  keyspaceName, keyspaceOptions));

        assertTrue(rows.wasApplied());

        String warning = rows.getAllExecutionInfo().get(0).getWarnings().get(0);
        assertThat(warning, containsString("The unsupported graph property 'graph_engine' was ignored."));

        assertNoGraphEngineKeyspaceProperty(keyspaceName);
    }

    private void assertNoGraphEngineKeyspaceProperty(String tableName) throws Throwable
    {
        ResultSet result = executeNet("DESCRIBE KEYSPACE " + tableName);

        String createStatement = result.one().getString("create_statement");
        assertThat(createStatement, not(containsString("graph_engine")));
    }
}
