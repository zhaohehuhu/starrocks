// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/DeleteStmtTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.collect.Lists;
import com.starrocks.mysql.privilege.MockedAuth;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.DeleteStmt;
import com.starrocks.sql.ast.PartitionNames;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DeleteStmtTest {
    @Mocked
    private ConnectContext ctx;

    @BeforeEach
    public void setUp() {
        MockedAuth.mockedConnectContext(ctx, "root", "192.168.1.1");
    }

    @Test
    public void getMethodTest() {
        BinaryPredicate wherePredicate = new BinaryPredicate(BinaryType.EQ, new SlotRef(null, "k1"),
                new StringLiteral("abc"));
        DeleteStmt deleteStmt = new DeleteStmt(new TableName("testDb", "testTbl"),
                new PartitionNames(false, Lists.newArrayList("partition")), wherePredicate);

        Assertions.assertEquals("testDb", deleteStmt.getTableName().getDb());
        Assertions.assertEquals("testTbl", deleteStmt.getTableName().getTbl());
        Assertions.assertEquals(Lists.newArrayList("partition"), deleteStmt.getPartitionNamesList());
    }
}
