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

package org.apache.cassandra.index.sai.utils;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TreeFormatterTest
{
    static class TreeNode
    {
        final String label;
        final List<TreeNode> children;

        TreeNode(String label, List<TreeNode> children)
        {
            this.label = label;
            this.children = children;
        }
    }

    @Test
    public void formatTree()
    {
        TreeNode root = new TreeNode("root", List.of(
            new TreeNode("child 1", List.of(
                new TreeNode("child 1a", Collections.emptyList()),
                new TreeNode("child 1b", Collections.emptyList()))),
            new TreeNode("child 2", List.of(
                new TreeNode("child 2a", Collections.emptyList()),
                new TreeNode("child 2b", Collections.emptyList())))));

        TreeFormatter<TreeNode> formatter = new TreeFormatter<>(t -> t.label, t -> t.children);
        String formattedTree = formatter.format(root);

        assertEquals("root\n" +
                     " ├─ child 1\n" +
                     " │   ├─ child 1a\n" +
                     " │   └─ child 1b\n" +
                     " └─ child 2\n" +
                     "     ├─ child 2a\n" +
                     "     └─ child 2b\n", formattedTree);
    }

    @Test
    public void formatTreeWithMultiLineNodes() {
        TreeNode root = new TreeNode("root line 1\nroot line 2", List.of(
        new TreeNode("child 1\nchild 1 line 2", List.of(
            new TreeNode("child 1a", Collections.emptyList()),
            new TreeNode("child 1b", Collections.emptyList()))),
        new TreeNode("child 2\nchild 2 line 2", Collections.emptyList())));

        TreeFormatter<TreeNode> formatter = new TreeFormatter<>(t -> t.label, t -> t.children);
        String formattedTree = formatter.format(root);

        assertEquals("root line 1\n" +
                     "root line 2\n" +
                     " ├─ child 1\n" +
                     " │  child 1 line 2\n" +
                     " │   ├─ child 1a\n" +
                     " │   └─ child 1b\n" +
                     " └─ child 2\n" +
                     "    child 2 line 2\n", formattedTree);
    }

}