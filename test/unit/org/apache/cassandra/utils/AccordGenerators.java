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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import accord.local.Command;
import accord.primitives.Deps;
import accord.primitives.FullRoute;
import accord.primitives.KeyDeps;
import accord.primitives.PartialTxn;
import accord.primitives.Range;
import accord.primitives.RangeDeps;
import accord.primitives.Ranges;
import accord.primitives.Timestamp;
import accord.primitives.TxnId;
import accord.utils.AccordGens;
import accord.utils.Gen;
import accord.utils.Gens;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.dht.AccordSplitter;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.service.accord.AccordTestUtils;
import org.apache.cassandra.service.accord.TokenRange;
import org.apache.cassandra.service.accord.api.AccordRoutingKey;
import org.apache.cassandra.service.accord.api.PartitionKey;
import org.quicktheories.impl.JavaRandom;

import static accord.utils.AccordGens.txnIds;
import static org.apache.cassandra.service.accord.AccordTestUtils.createPartialTxn;

public class AccordGenerators
{
    private static final Gen<IPartitioner> PARTITIONER_GEN = fromQT(CassandraGenerators.partitioners());

    private AccordGenerators()
    {
    }

    public static Gen<IPartitioner> partitioner()
    {
        return PARTITIONER_GEN;
    }

    private enum SupportedCommandTypes
    {notDefined, preaccepted, committed}

    public static Gen<Command> commands()
    {
        Gen<TxnId> ids = txnIds();
        //TODO switch to Status once all types are supported
        Gen<SupportedCommandTypes> supportedTypes = Gens.enums().all(SupportedCommandTypes.class);
        //TODO goes against fuzz testing, and also limits to a very specific table existing...
        // There is a branch that can generate random transactions, so maybe look into that?
        PartialTxn txn = createPartialTxn(0);
        FullRoute<?> route = txn.keys().toRoute(txn.keys().get(0).someIntersectingRoutingKey(null));

        return rs -> {
            TxnId id = ids.next(rs);
            Timestamp executeAt = id;
            if (rs.nextBoolean())
                executeAt = ids.next(rs);
            SupportedCommandTypes targetType = supportedTypes.next(rs);
            switch (targetType)
            {
                case notDefined:
                    return AccordTestUtils.Commands.notDefined(id, txn);
                case preaccepted:
                    return AccordTestUtils.Commands.preaccepted(id, txn, executeAt);
                case committed:
                    return AccordTestUtils.Commands.committed(id, txn, executeAt);
                default:
                    throw new UnsupportedOperationException("Unexpected type: " + targetType);
            }
        };
    }

    public static Gen<PartitionKey> keys()
    {
        return keys(fromQT(Generators.IDENTIFIER_GEN),
                    fromQT(CassandraGenerators.TABLE_ID_GEN),
                    fromQT(CassandraGenerators.decoratedKeys()));
    }

    public static Gen<PartitionKey> keys(IPartitioner partitioner)
    {
        return keys(fromQT(Generators.IDENTIFIER_GEN),
                    fromQT(CassandraGenerators.TABLE_ID_GEN),
                    fromQT(CassandraGenerators.decoratedKeys(ignore -> partitioner)));
    }

    public static Gen<PartitionKey> keys(Gen<String> keyspace, Gen<TableId> tableId, Gen<DecoratedKey> key)
    {
        return rs -> new PartitionKey(keyspace.next(rs), tableId.next(rs), key.next(rs));
    }

    public static Gen<AccordRoutingKey> routingKeyGen(Gen<String> keyspace, Gen<Token> tokenGen)
    {
        return rs -> {
            String ks = keyspace.next(rs);
            if (rs.nextBoolean()) return new AccordRoutingKey.TokenKey(ks, tokenGen.next(rs));
            else return rs.nextBoolean() ? AccordRoutingKey.SentinelKey.min(ks) : AccordRoutingKey.SentinelKey.max(ks);
        };
    }

    public static Gen<Range> range()
    {
        return PARTITIONER_GEN.flatMap(partitioner -> range(fromQT(Generators.IDENTIFIER_GEN), fromQT(CassandraGenerators.token(partitioner))));
    }

    public static Gen<Range> range(IPartitioner partitioner)
    {
        return range(fromQT(Generators.IDENTIFIER_GEN), fromQT(CassandraGenerators.token(partitioner)));
    }

    public static Gen<Range> range(Gen<String> keyspace, Gen<Token> tokenGen)
    {
        return rs -> {
            String ks = keyspace.next(rs);
            Gen<AccordRoutingKey> gen = routingKeyGen(Gens.constant(ks), tokenGen);
            AccordRoutingKey a = gen.next(rs);
            AccordRoutingKey b = gen.next(rs);
            while (a.equals(b))
                b = gen.next(rs);
            if (a.compareTo(b) < 0) return new TokenRange(a, b);
            else                    return new TokenRange(b, a);
        };
    }

    public static Gen<Ranges> ranges()
    {
        // javac couldn't pick the right constructor with HashSet::new, so had to create new lambda...
        return ranges(Gens.lists(fromQT(Generators.IDENTIFIER_GEN)).unique().ofSizeBetween(1, 10).map(l -> new HashSet<>(l)), PARTITIONER_GEN);
    }

    public static Gen<Ranges> ranges(Gen<Set<String>> keyspaceGen, Gen<IPartitioner> partitionerGen)
    {
        return rs -> {
            Set<String> keyspaces = keyspaceGen.next(rs);
            IPartitioner partitioner = partitionerGen.next(rs);
            List<Range> ranges = new ArrayList<>();
            int numSplits = rs.nextInt(10, 100);
            TokenRange range = new TokenRange(AccordRoutingKey.SentinelKey.min(""), AccordRoutingKey.SentinelKey.max(""));
            AccordSplitter splitter = partitioner.accordSplitter().apply(Ranges.of(range));
            BigInteger size = splitter.sizeOf(range);
            BigInteger update = splitter.divide(size, numSplits);
            BigInteger offset = BigInteger.ZERO;
            while (offset.compareTo(size) < 0)
            {
                BigInteger end = offset.add(update);
                TokenRange r = (TokenRange) splitter.subRange(range, offset, end);
                for (String ks : keyspaces)
                {
                    ranges.add(r.withKeyspace(ks));
                }
                offset = end;
            }
            return Ranges.of(ranges.toArray(new Range[0]));
        };
    }

    public static Gen<Ranges> ranges(IPartitioner partitioner)
    {
        return ranges(Gens.lists(fromQT(Generators.IDENTIFIER_GEN)).unique().ofSizeBetween(1, 10).map(l -> new HashSet<>(l)), ignore -> partitioner);
    }

    public static Gen<KeyDeps> keyDepsGen()
    {
        return AccordGens.keyDeps(AccordGenerators.keys());
    }

    public static Gen<KeyDeps> keyDepsGen(IPartitioner partitioner)
    {
        return AccordGens.keyDeps(AccordGenerators.keys(partitioner));
    }

    public static Gen<RangeDeps> rangeDepsGen()
    {
        return AccordGens.rangeDeps(AccordGenerators.range());
    }

    public static Gen<RangeDeps> rangeDepsGen(IPartitioner partitioner)
    {
        return AccordGens.rangeDeps(AccordGenerators.range(partitioner));
    }

    public static Gen<Deps> depsGen()
    {
        return AccordGens.deps(keyDepsGen(), rangeDepsGen());
    }

    public static Gen<Deps> depsGen(IPartitioner partitioner)
    {
        return AccordGens.deps(keyDepsGen(partitioner), rangeDepsGen(partitioner));
    }

    public static <T> Gen<T> fromQT(org.quicktheories.core.Gen<T> qt)
    {
        return rs -> {
            JavaRandom r = new JavaRandom(rs.asJdkRandom());
            return qt.generate(r);
        };
    }
}
