/*
 * Copyright KhulnaSoft, Ltd.
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
package org.apache.cassandra.index.sai.cql.datamodels;

import java.util.List;

import org.junit.runners.Parameterized;

public class QueryRowDeletionsWithCompositePartitionKeyTest extends QueryRowDeletionsTester
{
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> params()
    {
        return allIndexVersionsParams(SingleNodeQueryTester::compositePartitionKeyParams);
    }
}
