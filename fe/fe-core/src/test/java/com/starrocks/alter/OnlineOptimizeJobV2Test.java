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

package com.starrocks.alter;

import com.starrocks.alter.AlterJobV2.JobState;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.GlobalStateMgrTestUtil;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Replica;
import com.starrocks.common.Config;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.ThreadUtil;
import com.starrocks.scheduler.Constants;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.DDLTestBase;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class OnlineOptimizeJobV2Test extends DDLTestBase {
    private static final String TEST_FILE_NAME = OnlineOptimizeJobV2Test.class.getCanonicalName();
    private AlterTableStmt alterTableStmt;

    private static final Logger LOG = LogManager.getLogger(OnlineOptimizeJobV2Test.class);

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        String stmt = "alter table testTable7 distributed by hash(v1)";
        alterTableStmt = (AlterTableStmt) UtFrameUtils.parseStmtWithNewParser(stmt, starRocksAssert.getCtx());
        Config.enable_online_optimize_table = true;
    }

    @AfterEach
    public void clear() {
        GlobalStateMgr.getCurrentState().getSchemaChangeHandler().clearJobs();
        Config.enable_online_optimize_table = false;
    }

    @Test
    public void testOptimizeTable() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTable(db.getFullName(), GlobalStateMgrTestUtil.testTable7);

        schemaChangeHandler.process(alterTableStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        Assertions.assertEquals(OlapTableState.OPTIMIZE, olapTable.getState());
    }

    // start a schema change, then finished
    @Test
    public void testOptimizeTableFinish() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTable(db.getFullName(), GlobalStateMgrTestUtil.testTable7);
        Partition testPartition = olapTable.getPartition(GlobalStateMgrTestUtil.testTable7);

        schemaChangeHandler.process(alterTableStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        OnlineOptimizeJobV2 optimizeJob = (OnlineOptimizeJobV2) alterJobsV2.values().stream().findAny().get();

        // runPendingJob
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.WAITING_TXN, optimizeJob.getJobState());

        // runWaitingTxnJob
        optimizeJob.runWaitingTxnJob();
        Assertions.assertEquals(JobState.RUNNING, optimizeJob.getJobState());

        // runRunningJob
        List<OptimizeTask> optimizeTasks = optimizeJob.getOptimizeTasks();
        for (OptimizeTask optimizeTask : optimizeTasks) {
            optimizeTask.setOptimizeTaskState(Constants.TaskRunState.SUCCESS);
        }
        optimizeJob.runRunningJob();

        // finish alter tasks
        Assertions.assertEquals(JobState.FINISHED, optimizeJob.getJobState());
    }

    @Test
    public void testOptimizeTableFailed() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTable(db.getFullName(), GlobalStateMgrTestUtil.testTable7);

        schemaChangeHandler.process(alterTableStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        OnlineOptimizeJobV2 optimizeJob = (OnlineOptimizeJobV2) alterJobsV2.values().stream().findAny().get();

        // runPendingJob
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.WAITING_TXN, optimizeJob.getJobState());

        // runWaitingTxnJob
        optimizeJob.runWaitingTxnJob();
        Assertions.assertEquals(JobState.RUNNING, optimizeJob.getJobState());

        int retryCount = 0;
        int maxRetry = 5;

        try {
            optimizeJob.runRunningJob();
            while (retryCount < maxRetry) {
                ThreadUtil.sleepAtLeastIgnoreInterrupts(2000L);
                if (optimizeJob.getJobState() == JobState.CANCELLED) {
                    break;
                }
                retryCount++;
                LOG.info("testOptimizeTable is waiting for JobState retryCount:" + retryCount);
            }
            optimizeJob.cancel("");
        } catch (AlterCancelException e) {
            optimizeJob.cancel(e.getMessage());
        }

        // finish alter tasks
        Assertions.assertEquals(JobState.CANCELLED, optimizeJob.getJobState());

        OnlineOptimizeJobV2 replayOptimizeJob = new OnlineOptimizeJobV2(
                    optimizeJob.getJobId(), db.getId(), olapTable.getId(), olapTable.getName(), 1000);
        replayOptimizeJob.replay(optimizeJob);
    }

    @Test
    public void testSchemaChangeWhileTabletNotStable() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTable(db.getFullName(), GlobalStateMgrTestUtil.testTable7);
        Partition testPartition = olapTable.getPartition(GlobalStateMgrTestUtil.testTable7);

        schemaChangeHandler.process(alterTableStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        OnlineOptimizeJobV2 optimizeJob = (OnlineOptimizeJobV2) alterJobsV2.values().stream().findAny().get();

        MaterializedIndex baseIndex = testPartition.getDefaultPhysicalPartition().getBaseIndex();
        LocalTablet baseTablet = (LocalTablet) baseIndex.getTablets().get(0);
        List<Replica> replicas = baseTablet.getImmutableReplicas();
        Replica replica1 = replicas.get(0);

        // runPendingJob
        replica1.setState(Replica.ReplicaState.DECOMMISSION);
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.PENDING, optimizeJob.getJobState());

        // table is stable runPendingJob again
        replica1.setState(Replica.ReplicaState.NORMAL);
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.WAITING_TXN, optimizeJob.getJobState());
    }

    @Test
    public void testSerializeOfOptimizeJob() throws IOException {
        // prepare file
        File file = new File(TEST_FILE_NAME);
        file.createNewFile();
        file.deleteOnExit();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        OnlineOptimizeJobV2 optimizeJobV2 = new OnlineOptimizeJobV2(1, 1, 1, "test", 600000);
        Deencapsulation.setField(optimizeJobV2, "jobState", AlterJobV2.JobState.FINISHED);

        // write schema change job
        optimizeJobV2.write(out);
        out.flush();
        out.close();

        DataInputStream in = new DataInputStream(new FileInputStream(file));
        OnlineOptimizeJobV2 result = (OnlineOptimizeJobV2) AlterJobV2.read(in);
        Assertions.assertEquals(1, result.getJobId());
        Assertions.assertEquals(AlterJobV2.JobState.FINISHED, result.getJobState());
    }

    @Test
    public void testOptimizeReplay() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTable(db.getFullName(), GlobalStateMgrTestUtil.testTable7);

        schemaChangeHandler.process(alterTableStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        OnlineOptimizeJobV2 optimizeJob = (OnlineOptimizeJobV2) alterJobsV2.values().stream().findAny().get();

        OnlineOptimizeJobV2 replayOptimizeJob = new OnlineOptimizeJobV2(
                    optimizeJob.getJobId(), db.getId(), olapTable.getId(), olapTable.getName(), 1000);

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.PENDING, replayOptimizeJob.getJobState());

        // runPendingJob
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.WAITING_TXN, optimizeJob.getJobState());

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.WAITING_TXN, replayOptimizeJob.getJobState());

        // runWaitingTxnJob
        optimizeJob.runWaitingTxnJob();
        Assertions.assertEquals(JobState.RUNNING, optimizeJob.getJobState());

        // runRunningJob
        List<OptimizeTask> optimizeTasks = optimizeJob.getOptimizeTasks();
        for (OptimizeTask optimizeTask : optimizeTasks) {
            optimizeTask.setOptimizeTaskState(Constants.TaskRunState.SUCCESS);
        }
        try {
            optimizeJob.runRunningJob();
        } catch (Exception e) {
            LOG.info(e.getMessage());
        }

        // finish alter tasks
        Assertions.assertEquals(JobState.FINISHED, optimizeJob.getJobState());

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.FINISHED, replayOptimizeJob.getJobState());
    }

    @Test
    public void testOptimizeReplayPartialSuccess() throws Exception {
        SchemaChangeHandler schemaChangeHandler = GlobalStateMgr.getCurrentState().getSchemaChangeHandler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(GlobalStateMgrTestUtil.testDb1);
        OlapTable olapTable =
                    (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "testTable2");

        String stmt = "alter table testTable2 distributed by hash(v1)";
        AlterTableStmt alterStmt = (AlterTableStmt) UtFrameUtils.parseStmtWithNewParser(stmt, starRocksAssert.getCtx());
        schemaChangeHandler.process(alterStmt.getAlterClauseList(), db, olapTable);
        Map<Long, AlterJobV2> alterJobsV2 = schemaChangeHandler.getAlterJobsV2();
        Assertions.assertEquals(1, alterJobsV2.size());
        OnlineOptimizeJobV2 optimizeJob = (OnlineOptimizeJobV2) alterJobsV2.values().stream().findAny().get();

        OnlineOptimizeJobV2 replayOptimizeJob = new OnlineOptimizeJobV2(
                    optimizeJob.getJobId(), db.getId(), olapTable.getId(), olapTable.getName(), 1000);

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.PENDING, replayOptimizeJob.getJobState());

        // runPendingJob
        optimizeJob.runPendingJob();
        Assertions.assertEquals(JobState.WAITING_TXN, optimizeJob.getJobState());

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.WAITING_TXN, replayOptimizeJob.getJobState());

        // runWaitingTxnJob
        optimizeJob.runWaitingTxnJob();
        Assertions.assertEquals(JobState.RUNNING, optimizeJob.getJobState());

        // runRunningJob
        List<OptimizeTask> optimizeTasks = optimizeJob.getOptimizeTasks();
        Assertions.assertEquals(2, optimizeTasks.size());
        optimizeTasks.get(0).setOptimizeTaskState(Constants.TaskRunState.SUCCESS);
        optimizeTasks.get(1).setOptimizeTaskState(Constants.TaskRunState.FAILED);
        optimizeJob.runRunningJob();

        // finish alter tasks
        Assertions.assertEquals(JobState.FINISHED, optimizeJob.getJobState());

        replayOptimizeJob.replay(optimizeJob);
        Assertions.assertEquals(JobState.FINISHED, replayOptimizeJob.getJobState());
    }
}
