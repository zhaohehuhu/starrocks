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

package com.starrocks.sql;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.alter.SchemaChangeHandler;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TableName;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MysqlTable;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableFunctionTable;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.profile.Timer;
import com.starrocks.common.profile.Tracers;
import com.starrocks.planner.BlackHoleTableSink;
import com.starrocks.planner.DataSink;
import com.starrocks.planner.HiveTableSink;
import com.starrocks.planner.IcebergTableSink;
import com.starrocks.planner.MysqlTableSink;
import com.starrocks.planner.OlapTableSink;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.TableFunctionTableSink;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.AnalyzeState;
import com.starrocks.sql.analyzer.ExpressionAnalyzer;
import com.starrocks.sql.analyzer.Field;
import com.starrocks.sql.analyzer.PlannerMetaLocker;
import com.starrocks.sql.analyzer.RelationFields;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.analyzer.Scope;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.DefaultValueExpr;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.ValuesRelation;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.common.TypeManager;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.Optimizer;
import com.starrocks.sql.optimizer.OptimizerFactory;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.DistributionProperty;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.GatherDistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.Ordering;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.base.RoundRobinDistributionSpec;
import com.starrocks.sql.optimizer.base.SortProperty;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.rewrite.scalar.FoldConstantsRule;
import com.starrocks.sql.optimizer.rewrite.scalar.ScalarOperatorRewriteRule;
import com.starrocks.sql.optimizer.statistics.ColumnDict;
import com.starrocks.sql.optimizer.statistics.IDictManager;
import com.starrocks.sql.optimizer.transformer.ExpressionMapping;
import com.starrocks.sql.optimizer.transformer.LogicalPlan;
import com.starrocks.sql.optimizer.transformer.OptExprBuilder;
import com.starrocks.sql.optimizer.transformer.RelationTransformer;
import com.starrocks.sql.optimizer.transformer.SqlToScalarOperatorTranslator;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.sql.plan.PlanFragmentBuilder;
import com.starrocks.thrift.TPartialUpdateMode;
import com.starrocks.thrift.TResultSinkType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.iceberg.NullOrder;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.SortDirection;
import org.apache.iceberg.SortField;
import org.apache.iceberg.SortOrder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.starrocks.catalog.DefaultExpr.isValidDefaultFunction;
import static com.starrocks.sql.optimizer.rule.mv.MVUtils.MATERIALIZED_VIEW_NAME_PREFIX;

public class InsertPlanner {
    // Only for unit test
    public static boolean enableSingleReplicationShuffle = false;
    private boolean shuffleServiceEnable = false;
    private boolean forceReplicatedStorage = false;
    private boolean useOptimisticLock;
    private PlannerMetaLocker plannerMetaLocker;

    private List<Column> outputBaseSchema;
    private List<Column> outputFullSchema;

    private static final Logger LOG = LogManager.getLogger(InsertPlanner.class);

    public InsertPlanner() {
        this.useOptimisticLock = false;
    }

    public InsertPlanner(PlannerMetaLocker plannerMetaLocker, boolean optimisticLock) {
        this.useOptimisticLock = optimisticLock;
        this.plannerMetaLocker = plannerMetaLocker;
    }

    private enum GenColumnDependency {
        NO_DEPENDENCY,
        NONE_DEPEND_ON_TARGET_COLUMNS,
        ALL_DEPEND_ON_TARGET_COLUMNS,
        PARTIALLY_DEPEND_ON_TARGET_COLUMNS
    }

    private static GenColumnDependency getDependencyType(Column column,
                                                         Set<String> targetColumns,
                                                         Map<ColumnId, Column> allColumns) {
        List<SlotRef> slots = column.getGeneratedColumnRef(allColumns);
        if (slots.isEmpty()) {
            return GenColumnDependency.NO_DEPENDENCY;
        }
        boolean allDependOnTargetColumns = true;
        boolean noneDependOnTargetColumns = true;
        for (SlotRef slot : slots) {
            String originName = slot.getColumnName().toLowerCase();
            if (targetColumns.contains(originName)) {
                noneDependOnTargetColumns = false;
            } else {
                allDependOnTargetColumns = false;
            }
        }
        if (allDependOnTargetColumns) {
            return GenColumnDependency.ALL_DEPEND_ON_TARGET_COLUMNS;
        }
        if (noneDependOnTargetColumns) {
            return GenColumnDependency.NONE_DEPEND_ON_TARGET_COLUMNS;
        }
        return GenColumnDependency.PARTIALLY_DEPEND_ON_TARGET_COLUMNS;
    }

    private void inferOutputSchemaForPartialUpdate(InsertStmt insertStmt) {
        outputBaseSchema = new ArrayList<>();
        outputFullSchema = new ArrayList<>();
        Set<String> legalGeneratedColumnDependencies = new HashSet<>();
        Set<String> outputColumnNames = insertStmt.getTargetColumnNames().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        Set<String> baseSchemaNames = insertStmt.getTargetTable().getBaseSchema().stream()
                .map(Column::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        OlapTable targetTable = (OlapTable) insertStmt.getTargetTable();
        for (Column column : targetTable.getFullSchema()) {
            String columnName = column.getName().toLowerCase();
            if (outputColumnNames.contains(columnName) || column.isKey()) {
                if (baseSchemaNames.contains(columnName)) {
                    outputBaseSchema.add(column);
                }
                outputFullSchema.add(column);
                legalGeneratedColumnDependencies.add(columnName);
                continue;
            }
            if (column.isAutoIncrement() || column.getDefaultExpr() != null) {
                if (baseSchemaNames.contains(columnName)) {
                    outputBaseSchema.add(column);
                }
                outputFullSchema.add(column);
                continue;
            }
            if (column.isGeneratedColumn()) {
                // check if the generated column only depends on the columns in the output schema
                // if so, add it to the output schema
                // if is not related to target columns at all, skip it (TODO in future)
                // else raise error
                switch (getDependencyType(column, legalGeneratedColumnDependencies, targetTable.getIdToColumn())) {
                    case NO_DEPENDENCY:
                        // should not happen, just skip
                        continue;
                    case ALL_DEPEND_ON_TARGET_COLUMNS:
                        if (baseSchemaNames.contains(columnName)) {
                            outputBaseSchema.add(column);
                        }
                        outputFullSchema.add(column);
                        continue;
                    case NONE_DEPEND_ON_TARGET_COLUMNS: // TODO: handle this case
                    case PARTIALLY_DEPEND_ON_TARGET_COLUMNS:
                        ErrorReport.reportSemanticException(ErrorCode.ERR_MISSING_DEPENDENCY_FOR_GENERATED_COLUMN,
                                column.getName());
                }
            }
            if (column.isNameWithPrefix(SchemaChangeHandler.SHADOW_NAME_PREFIX)) {
                String originName = Column.removeNamePrefix(column.getName());
                if (outputColumnNames.contains(originName.toLowerCase())) {
                    if (baseSchemaNames.contains(column.getName())) {
                        outputBaseSchema.add(column);
                    }
                    outputFullSchema.add(column);
                }
                continue;
            }
        }
    }

    public ExecPlan plan(InsertStmt insertStmt, ConnectContext session) {
        QueryRelation queryRelation = insertStmt.getQueryStatement().getQueryRelation();
        List<ColumnRefOperator> outputColumns = new ArrayList<>();
        Table targetTable = insertStmt.getTargetTable();

        if (insertStmt.usePartialUpdate()) {
            inferOutputSchemaForPartialUpdate(insertStmt);
        } else {
            outputBaseSchema = targetTable.getBaseSchema();
            outputFullSchema = targetTable.getFullSchema();
        }

        //1. Process the literal value of the insert values type and cast it into the type of the target table
        if (queryRelation instanceof ValuesRelation) {
            castLiteralToTargetColumnsType(insertStmt);
        }

        //2. Build Logical plan
        ColumnRefFactory columnRefFactory = new ColumnRefFactory();
        LogicalPlan logicalPlan;
        try (Timer ignore = Tracers.watchScope("Transform")) {
            logicalPlan = new RelationTransformer(columnRefFactory, session).transform(queryRelation);
        }

        //3. Fill in the default value and NULL
        OptExprBuilder optExprBuilder = fillDefaultValue(logicalPlan, columnRefFactory, insertStmt, outputColumns);

        //4. Fill key partition columns constant value for data lake format table (hive/iceberg/hudi/delta_lake)
        if (insertStmt.isSpecifyKeyPartition()) {
            optExprBuilder = fillKeyPartitionsColumn(columnRefFactory, insertStmt, outputColumns, optExprBuilder);
        }

        //5. Fill in the generated columns
        optExprBuilder = fillGeneratedColumns(columnRefFactory, insertStmt, outputColumns, optExprBuilder, session);

        //6. Fill in the shadow column
        optExprBuilder = fillShadowColumns(columnRefFactory, insertStmt, outputColumns, optExprBuilder, session);

        //7. Cast output columns type to target type
        optExprBuilder =
                castOutputColumnsTypeToTargetColumns(columnRefFactory, insertStmt, outputColumns, optExprBuilder);

        //8. Optimize logical plan and build physical plan
        logicalPlan = new LogicalPlan(optExprBuilder, outputColumns, logicalPlan.getCorrelation());

        // TODO: remove forceDisablePipeline when all the operators support pipeline engine.
        SessionVariable currentVariable = (SessionVariable) session.getSessionVariable().clone();
        session.setSessionVariable(currentVariable);
        boolean isEnablePipeline = session.getSessionVariable().isEnablePipelineEngine();
        boolean canUsePipeline = isEnablePipeline && DataSink.canTableSinkUsePipeline(targetTable);
        boolean forceDisablePipeline = isEnablePipeline && !canUsePipeline;
        boolean enableMVRewrite = currentVariable.isEnableMaterializedViewRewriteForInsert() &&
                currentVariable.isEnableMaterializedViewRewrite();
        try (Timer ignore = Tracers.watchScope("InsertPlanner")) {
            if (forceDisablePipeline) {
                session.getSessionVariable().setEnablePipelineEngine(false);
            }
            // Non-query must use the strategy assign scan ranges per driver sequence, which local shuffle agg cannot use.
            session.getSessionVariable().setEnableLocalShuffleAgg(false);
            session.getSessionVariable().setEnableMaterializedViewRewrite(enableMVRewrite);

            ExecPlan execPlan =
                    useOptimisticLock ?
                            buildExecPlanWithRetry(insertStmt, session, outputColumns, logicalPlan, columnRefFactory,
                                    queryRelation, targetTable) :
                            buildExecPlan(insertStmt, session, outputColumns, logicalPlan, columnRefFactory,
                                    queryRelation,
                                    targetTable);

            DescriptorTable descriptorTable = execPlan.getDescTbl();
            TupleDescriptor tupleDesc = descriptorTable.createTupleDescriptor();

            List<Pair<Integer, ColumnDict>> globalDicts = Lists.newArrayList();
            long tableId = targetTable.getId();
            for (Column column : outputFullSchema) {
                SlotDescriptor slotDescriptor = descriptorTable.addSlotDescriptor(tupleDesc);
                slotDescriptor.setIsMaterialized(true);
                slotDescriptor.setType(column.getType());
                slotDescriptor.setColumn(column);
                slotDescriptor.setIsNullable(column.isAllowNull());
                if (column.getType().isVarchar() &&
                        IDictManager.getInstance().hasGlobalDict(tableId, column.getColumnId())) {
                    Optional<ColumnDict> dict = IDictManager.getInstance().getGlobalDict(tableId, column.getColumnId());
                    dict.ifPresent(
                            columnDict -> globalDicts.add(new Pair<>(slotDescriptor.getId().asInt(), columnDict)));
                }
            }
            tupleDesc.computeMemLayout();

            DataSink dataSink;
            if (targetTable instanceof OlapTable) {
                OlapTable olapTable = (OlapTable) targetTable;
                boolean enableAutomaticPartition;
                List<Long> targetPartitionIds = insertStmt.getTargetPartitionIds();
                if (insertStmt.isSystem() && insertStmt.isPartitionNotSpecifiedInOverwrite()) {
                    Preconditions.checkState(!CollectionUtils.isEmpty(targetPartitionIds));
                    enableAutomaticPartition = olapTable.supportedAutomaticPartition();
                } else if (insertStmt.isDynamicOverwrite()) {
                    Preconditions.checkState(CollectionUtils.isEmpty(targetPartitionIds));
                    enableAutomaticPartition = olapTable.supportedAutomaticPartition();
                } else if (insertStmt.isSpecifyPartitionNames()) {
                    Preconditions.checkState(!CollectionUtils.isEmpty(targetPartitionIds));
                    enableAutomaticPartition = false;
                } else if (insertStmt.isStaticKeyPartitionInsert()) {
                    enableAutomaticPartition = false;
                } else {
                    Preconditions.checkState(!CollectionUtils.isEmpty(targetPartitionIds));
                    enableAutomaticPartition = olapTable.supportedAutomaticPartition();
                }
                boolean nullExprInAutoIncrement = false;
                // In INSERT INTO SELECT, if AUTO_INCREMENT column
                // is specified, the column values must not be NULL
                if (!(queryRelation instanceof ValuesRelation) &&
                        !(targetTable instanceof MaterializedView)) {
                    boolean specifyAutoIncrementColumn = false;
                    if (insertStmt.getTargetColumnNames() != null) {
                        for (String colName : insertStmt.getTargetColumnNames()) {
                            for (Column col : olapTable.getBaseSchema()) {
                                if (col.isAutoIncrement() && col.getName().equals(colName)) {
                                    specifyAutoIncrementColumn = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (insertStmt.getTargetColumnNames() == null || specifyAutoIncrementColumn) {
                        nullExprInAutoIncrement = true;
                    }

                }
                dataSink = new OlapTableSink(olapTable, tupleDesc, targetPartitionIds,
                        olapTable.writeQuorum(),
                        forceReplicatedStorage ? true : olapTable.enableReplicatedStorage(),
                        nullExprInAutoIncrement, enableAutomaticPartition, session.getCurrentComputeResource());
                if (insertStmt.usePartialUpdate()) {
                    ((OlapTableSink) dataSink).setPartialUpdateMode(TPartialUpdateMode.AUTO_MODE);
                    if (insertStmt.autoIncrementPartialUpdate()) {
                        ((OlapTableSink) dataSink).setMissAutoIncrementColumn();
                    }
                }
                if (olapTable.getAutomaticBucketSize() > 0) {
                    ((OlapTableSink) dataSink).setAutomaticBucketSize(olapTable.getAutomaticBucketSize());
                }
                if (insertStmt.isDynamicOverwrite()) {
                    ((OlapTableSink) dataSink).setDynamicOverwrite(true);
                }
                if (insertStmt.isFromOverwrite()) {
                    ((OlapTableSink) dataSink).setIsFromOverwrite(true);
                }

                // if sink is OlapTableSink Assigned to Be execute this sql [cn execute OlapTableSink will crash]
                session.getSessionVariable().setPreferComputeNode(false);
                session.getSessionVariable().setUseComputeNodes(0);
                OlapTableSink olapTableSink = (OlapTableSink) dataSink;
                TableName catalogDbTable = insertStmt.getTableName();
                Database db = GlobalStateMgr.getCurrentState().getMetadataMgr().getDb(session, catalogDbTable.getCatalog(),
                        catalogDbTable.getDb());
                try {
                    olapTableSink.init(session.getExecutionId(), insertStmt.getTxnId(), db.getId(), session.getExecTimeout());
                    olapTableSink.complete();
                } catch (StarRocksException e) {
                    throw new SemanticException(e.getMessage());
                }
            } else if (insertStmt.getTargetTable() instanceof MysqlTable) {
                dataSink = new MysqlTableSink((MysqlTable) targetTable);
            } else if (targetTable instanceof IcebergTable) {
                descriptorTable.addReferencedTable(targetTable);
                dataSink = new IcebergTableSink((IcebergTable) targetTable, tupleDesc,
                        isKeyPartitionStaticInsert(insertStmt, queryRelation), session.getSessionVariable(),
                        insertStmt.getTargetBranch());
            } else if (targetTable instanceof HiveTable) {
                dataSink = new HiveTableSink((HiveTable) targetTable, tupleDesc,
                        isKeyPartitionStaticInsert(insertStmt, queryRelation), session.getSessionVariable());
            } else if (targetTable instanceof TableFunctionTable) {
                dataSink = new TableFunctionTableSink((TableFunctionTable) targetTable);
            } else if (targetTable.isBlackHoleTable()) {
                dataSink = new BlackHoleTableSink();
            } else {
                throw new SemanticException("Unknown table type " + insertStmt.getTargetTable().getType());
            }

            // enable spill for connector sink
            if (session.getSessionVariable().isEnableConnectorSinkSpill() && (targetTable instanceof IcebergTable
                    || targetTable instanceof HiveTable || targetTable instanceof TableFunctionTable)) {
                session.getSessionVariable().setEnableSpill(true);
                if (currentVariable.getConnectorSinkSpillMemLimitThreshold() < currentVariable.getSpillMemLimitThreshold()) {
                    currentVariable.setSpillMemLimitThreshold(currentVariable.getConnectorSinkSpillMemLimitThreshold());
                }
            }

            PlanFragment sinkFragment = execPlan.getFragments().get(0);
            if (canUsePipeline && (targetTable instanceof OlapTable || targetTable.isIcebergTable() ||
                    targetTable.isHiveTable() || targetTable.isTableFunctionTable())) {
                if (shuffleServiceEnable) {
                    // For shuffle insert into, we only support tablet sink dop = 1
                    // because for tablet sink dop > 1, local passthourgh exchange will influence the order of sending,
                    // which may lead to inconsisten replica for primary key.
                    // If you want to set tablet sink dop > 1, please enable single tablet loading and disable shuffle service
                    sinkFragment.setPipelineDop(1);
                } else {
                    if (ConnectContext.get().getSessionVariable().getEnableAdaptiveSinkDop()) {
                        sinkFragment.setPipelineDop(
                                ConnectContext.get().getSessionVariable().getSinkDegreeOfParallelism());
                    } else {
                        sinkFragment
                                .setPipelineDop(ConnectContext.get().getSessionVariable().getParallelExecInstanceNum());
                    }
                }

                if (targetTable instanceof OlapTable) {
                    sinkFragment.setHasOlapTableSink();
                    sinkFragment.setForceAssignScanRangesPerDriverSeq();
                } else if (targetTable.isHiveTable()) {
                    sinkFragment.setHasHiveTableSink();
                } else if (targetTable.isIcebergTable()) {
                    sinkFragment.setHasIcebergTableSink();
                } else if (targetTable.isTableFunctionTable()) {
                    sinkFragment.setHasTableFunctionTableSink();
                }

                sinkFragment.disableRuntimeAdaptiveDop();
                sinkFragment.setForceSetTableSinkDop();
            }
            sinkFragment.setSink(dataSink);
            sinkFragment.setLoadGlobalDicts(globalDicts);
            return execPlan;
        }
    }

    /**
     * The workhorse of InsertPlanner, which may takes a lot of time, so we would release the lock during planning
     */
    private ExecPlan buildExecPlanWithRetry(InsertStmt insertStmt, ConnectContext session,
                                            List<ColumnRefOperator> outputColumns,
                                            LogicalPlan logicalPlan, ColumnRefFactory columnRefFactory,
                                            QueryRelation queryRelation, Table targetTable) {
        boolean isSchemaValid = true;
        Set<OlapTable> olapTables = StatementPlanner.collectOriginalOlapTables(session, insertStmt);
        Stopwatch watch = Stopwatch.createStarted();

        for (int i = 0; i < Config.max_query_retry_time; i++) {
            long planStartTime = OptimisticVersion.generate();
            if (!isSchemaValid) {
                olapTables = StatementPlanner.reAnalyzeStmt(insertStmt, session, plannerMetaLocker);
            }

            // Release the lock during planning, and reacquire the lock before validating
            plannerMetaLocker.unlock();
            ExecPlan plan;
            try {
                plan = buildExecPlan(insertStmt, session, outputColumns, logicalPlan, columnRefFactory, queryRelation,
                        targetTable);
            } finally {
                try (Timer ignore2 = Tracers.watchScope("Lock")) {
                    StatementPlanner.lock(plannerMetaLocker);
                }
            }
            isSchemaValid =
                    olapTables.stream().allMatch(t -> OptimisticVersion.validateTableUpdate(t, planStartTime));
            if (isSchemaValid) {
                return plan;
            }
        }
        throw new StarRocksPlannerException(String.format("failed to generate plan for the statement after %dms",
                watch.elapsed(TimeUnit.MILLISECONDS)), ErrorType.INTERNAL_ERROR);
    }

    private ExecPlan buildExecPlan(InsertStmt insertStmt, ConnectContext session, List<ColumnRefOperator> outputColumns,
                                   LogicalPlan logicalPlan, ColumnRefFactory columnRefFactory,
                                   QueryRelation queryRelation, Table targetTable) {
        PhysicalPropertySet requiredPropertySet = createPhysicalPropertySet(insertStmt, outputColumns,
                session.getSessionVariable());
        OptExpression optimizedPlan;

        try (Timer ignore2 = Tracers.watchScope("Optimizer")) {
            Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.initContext(session, columnRefFactory));
            optimizedPlan = optimizer.optimize(
                    logicalPlan.getRoot(),
                    requiredPropertySet,
                    new ColumnRefSet(logicalPlan.getOutputColumn()));
        }

        //8. Build fragment exec plan
        boolean hasOutputFragment = ((queryRelation instanceof SelectRelation && queryRelation.hasLimit())
                || targetTable instanceof MysqlTable);
        ExecPlan execPlan;
        try (Timer ignore3 = Tracers.watchScope("PlanBuilder")) {
            execPlan = PlanFragmentBuilder.createPhysicalPlan(
                    optimizedPlan, session, logicalPlan.getOutputColumn(), columnRefFactory,
                    queryRelation.getColumnOutputNames(), TResultSinkType.MYSQL_PROTOCAL, hasOutputFragment);
        }
        return execPlan;
    }

    private void castLiteralToTargetColumnsType(InsertStmt insertStatement) {
        Preconditions.checkState(insertStatement.getQueryStatement().getQueryRelation() instanceof ValuesRelation,
                "must values");
        ValuesRelation values = (ValuesRelation) insertStatement.getQueryStatement().getQueryRelation();
        RelationFields fields = insertStatement.getQueryStatement().getQueryRelation().getRelationFields();

        for (int columnIdx = 0; columnIdx < outputBaseSchema.size(); ++columnIdx) {
            if (needToSkip(insertStatement, columnIdx)) {
                continue;
            }

            Column targetColumn = outputBaseSchema.get(columnIdx);
            if (targetColumn.isGeneratedColumn()) {
                continue;
            }
            boolean isAutoIncrement = targetColumn.isAutoIncrement();
            if (insertStatement.getTargetColumnNames() == null) {
                for (List<Expr> row : values.getRows()) {
                    if (isAutoIncrement && row.get(columnIdx).getType() == Type.NULL) {
                        throw new SemanticException(" `NULL` value is not supported for an AUTO_INCREMENT column: " +
                                targetColumn.getName() + " You can use `default` for an" +
                                " AUTO INCREMENT column");
                    }
                    if (row.get(columnIdx) instanceof DefaultValueExpr) {
                        if (isAutoIncrement) {
                            row.set(columnIdx, new NullLiteral());
                        } else {
                            row.set(columnIdx, new StringLiteral(targetColumn.calculatedDefaultValue()));
                        }
                    }
                    row.set(columnIdx, TypeManager.addCastExpr(row.get(columnIdx), targetColumn.getType()));
                }
                fields.getFieldByIndex(columnIdx).setType(targetColumn.getType());
            } else {
                int idx = insertStatement.getTargetColumnNames().indexOf(targetColumn.getName().toLowerCase());
                if (idx != -1) {
                    for (List<Expr> row : values.getRows()) {
                        if (isAutoIncrement && row.get(idx).getType() == Type.NULL) {
                            throw new SemanticException(
                                    " `NULL` value is not supported for an AUTO_INCREMENT column: " +
                                            targetColumn.getName() + " You can use `default` for an" +
                                            " AUTO INCREMENT column");
                        }
                        if (row.get(idx) instanceof DefaultValueExpr) {
                            if (isAutoIncrement) {
                                row.set(idx, new NullLiteral());
                            } else {
                                row.set(idx, new StringLiteral(targetColumn.calculatedDefaultValue()));
                            }
                        }
                        row.set(idx, TypeManager.addCastExpr(row.get(idx), targetColumn.getType()));
                    }
                    fields.getFieldByIndex(idx).setType(targetColumn.getType());
                }
            }
        }
    }

    private OptExprBuilder fillDefaultValue(LogicalPlan logicalPlan, ColumnRefFactory columnRefFactory,
                                            InsertStmt insertStatement, List<ColumnRefOperator> outputColumns) {
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = new HashMap<>();

        for (int columnIdx = 0; columnIdx < outputBaseSchema.size(); ++columnIdx) {
            if (needToSkip(insertStatement, columnIdx)) {
                continue;
            }

            Column targetColumn = outputBaseSchema.get(columnIdx);
            if (targetColumn.isGeneratedColumn()) {
                continue;
            }
            if (insertStatement.getTargetColumnNames() == null) {
                outputColumns.add(logicalPlan.getOutputColumn().get(columnIdx));
                columnRefMap.put(logicalPlan.getOutputColumn().get(columnIdx),
                        logicalPlan.getOutputColumn().get(columnIdx));
            } else {
                int idx = insertStatement.getTargetColumnNames().indexOf(targetColumn.getName().toLowerCase());
                if (idx == -1) {
                    ScalarOperator scalarOperator;
                    Column.DefaultValueType defaultValueType = targetColumn.getDefaultValueType();
                    if (defaultValueType == Column.DefaultValueType.NULL || targetColumn.isAutoIncrement()) {
                        scalarOperator = ConstantOperator.createNull(targetColumn.getType());
                    } else if (defaultValueType == Column.DefaultValueType.CONST) {
                        scalarOperator = ConstantOperator.createVarchar(targetColumn.calculatedDefaultValue());
                    } else if (defaultValueType == Column.DefaultValueType.VARY) {
                        if (isValidDefaultFunction(targetColumn.getDefaultExpr().getExpr())) {
                            scalarOperator = SqlToScalarOperatorTranslator.
                                    translate(targetColumn.getDefaultExpr().obtainExpr());
                        } else {
                            throw new SemanticException(
                                    "Column:" + targetColumn.getName() + " has unsupported default value:"
                                            + targetColumn.getDefaultExpr().getExpr());
                        }
                    } else {
                        throw new SemanticException("Unknown default value type:%s", defaultValueType.toString());
                    }
                    ColumnRefOperator col = columnRefFactory
                            .create(scalarOperator, scalarOperator.getType(), scalarOperator.isNullable());

                    outputColumns.add(col);
                    columnRefMap.put(col, scalarOperator);
                } else {
                    outputColumns.add(logicalPlan.getOutputColumn().get(idx));
                    columnRefMap.put(logicalPlan.getOutputColumn().get(idx), logicalPlan.getOutputColumn().get(idx));
                }
            }
        }
        return logicalPlan.getRootBuilder().withNewRoot(new LogicalProjectOperator(new HashMap<>(columnRefMap)));
    }

    private OptExprBuilder fillGeneratedColumns(ColumnRefFactory columnRefFactory, InsertStmt insertStatement,
                                                List<ColumnRefOperator> outputColumns, OptExprBuilder root,
                                                ConnectContext session) {
        Set<Column> baseSchema = Sets.newHashSet(outputBaseSchema);
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = new HashMap<>();

        for (int columnIdx = 0; columnIdx < outputFullSchema.size(); ++columnIdx) {
            Column targetColumn = outputFullSchema.get(columnIdx);

            if (targetColumn.isGeneratedColumn()) {
                // If fe restart and Insert INTO is executed, the re-analyze is needed.
                Expr expr = targetColumn.getGeneratedColumnExpr(insertStatement.getTargetTable().getIdToColumn());
                ExpressionAnalyzer.analyzeExpression(expr,
                        new AnalyzeState(), new Scope(RelationId.anonymous(), new RelationFields(
                                insertStatement.getTargetTable().getBaseSchema().stream()
                                        .map(col -> new Field(col.getName(),
                                                col.getType(), insertStatement.getTableName(), null))
                                        .collect(Collectors.toList()))), session);

                List<SlotRef> slots = new ArrayList<>();
                expr.collect(SlotRef.class, slots);

                ExpressionMapping expressionMapping =
                        new ExpressionMapping(new Scope(RelationId.anonymous(), new RelationFields()),
                                Lists.newArrayList());

                for (SlotRef slot : slots) {
                    String originName = slot.getColumnName();

                    Optional<Column> optOriginColumn = outputFullSchema.stream()
                            .filter(c -> c.nameEquals(originName, false)).findFirst();
                    Column originColumn = optOriginColumn.get();
                    ColumnRefOperator originColRefOp = outputColumns.get(outputFullSchema.indexOf(originColumn));

                    expressionMapping.put(slot, originColRefOp);
                }

                ScalarOperator scalarOperator =
                        SqlToScalarOperatorTranslator.translate(expr, expressionMapping, columnRefFactory);

                ColumnRefOperator columnRefOperator =
                        columnRefFactory.create(scalarOperator, scalarOperator.getType(), scalarOperator.isNullable());
                outputColumns.add(columnRefOperator);
                columnRefMap.put(columnRefOperator, scalarOperator);
            } else if (baseSchema.contains(outputFullSchema.get(columnIdx))) {
                ColumnRefOperator columnRefOperator = outputColumns.get(columnIdx);
                columnRefMap.put(columnRefOperator, columnRefOperator);
            }
        }

        return root.withNewRoot(new LogicalProjectOperator(new HashMap<>(columnRefMap)));
    }

    private OptExprBuilder fillShadowColumns(ColumnRefFactory columnRefFactory, InsertStmt insertStatement,
                                             List<ColumnRefOperator> outputColumns, OptExprBuilder root,
                                             ConnectContext session) {
        Set<Column> baseSchema = Sets.newHashSet(outputBaseSchema);
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = new HashMap<>();
        for (int columnIdx = 0; columnIdx < outputFullSchema.size(); ++columnIdx) {
            Column targetColumn = outputFullSchema.get(columnIdx);

            if (targetColumn.isNameWithPrefix(SchemaChangeHandler.SHADOW_NAME_PREFIX)) {
                if (targetColumn.isGeneratedColumn()) {
                    continue;
                }

                String originName = Column.removeNamePrefix(targetColumn.getName());
                Optional<Column> optOriginColumn = outputFullSchema.stream()
                        .filter(c -> c.nameEquals(originName, false)).findFirst();
                Preconditions.checkState(optOriginColumn.isPresent());
                Column originColumn = optOriginColumn.get();
                ColumnRefOperator originColRefOp = outputColumns.get(outputFullSchema.indexOf(originColumn));

                ColumnRefOperator columnRefOperator = columnRefFactory.create(
                        targetColumn.getName(), targetColumn.getType(), targetColumn.isAllowNull());

                outputColumns.add(columnRefOperator);
                columnRefMap.put(columnRefOperator, new CastOperator(targetColumn.getType(), originColRefOp, true));
                continue;
            }

            // Target column which starts with "mv" should not be treated as materialized view column when this column exists in base schema,
            // this could be created by user.
            if (targetColumn.isNameWithPrefix(MATERIALIZED_VIEW_NAME_PREFIX) &&
                    !baseSchema.contains(targetColumn)) {
                if (targetColumn.getDefineExpr() == null) {
                    Table targetTable = insertStatement.getTargetTable();
                    // Only olap table can have the synchronized materialized view.
                    OlapTable targetOlapTable = (OlapTable) targetTable;
                    MaterializedIndexMeta targetIndexMeta = null;
                    for (MaterializedIndexMeta indexMeta : targetOlapTable.getIndexIdToMeta().values()) {
                        if (indexMeta.getIndexId() == targetOlapTable.getBaseIndexId()) {
                            continue;
                        }
                        for (Column column : indexMeta.getSchema()) {
                            if (column.getName().equals(targetColumn.getName())) {
                                targetIndexMeta = indexMeta;
                                break;
                            }
                        }
                    }
                    String targetIndexMetaName = targetIndexMeta == null ? "" :
                            targetOlapTable.getIndexNameById(targetIndexMeta.getIndexId());
                    throw new SemanticException(
                            "The define expr of shadow column " + targetColumn.getName() + " is null, " +
                                    "please check the associated materialized view " + targetIndexMetaName
                                    + " of target table:" + insertStatement.getTargetTable().getName());
                }
                ExpressionMapping expressionMapping =
                        new ExpressionMapping(new Scope(RelationId.anonymous(), new RelationFields()),
                                Lists.newArrayList());

                List<SlotRef> slots = targetColumn.getRefColumns();
                for (SlotRef slot : slots) {
                    String originName = slot.getColumnName();
                    Optional<Column> optOriginColumn = outputFullSchema.stream()
                            .filter(c -> c.nameEquals(originName, false)).findFirst();
                    Preconditions.checkState(optOriginColumn.isPresent());
                    Column originColumn = optOriginColumn.get();
                    ColumnRefOperator originColRefOp = outputColumns.get(outputFullSchema.indexOf(originColumn));
                    expressionMapping.put(slot, originColRefOp);
                }

                ScalarOperator scalarOperator =
                        SqlToScalarOperatorTranslator.translate(targetColumn.getDefineExpr(), expressionMapping,
                                columnRefFactory);

                ColumnRefOperator columnRefOperator =
                        columnRefFactory.create(scalarOperator, scalarOperator.getType(), scalarOperator.isNullable());
                outputColumns.add(columnRefOperator);
                columnRefMap.put(columnRefOperator, scalarOperator);
                continue;
            }

            // columnIdx >= outputColumns.size() mean this is a new add schema change column
            if (columnIdx >= outputColumns.size()) {
                ColumnRefOperator columnRefOperator = columnRefFactory.create(
                        targetColumn.getName(), targetColumn.getType(), targetColumn.isAllowNull());
                outputColumns.add(columnRefOperator);

                Column.DefaultValueType defaultValueType = targetColumn.getDefaultValueType();
                if (defaultValueType == Column.DefaultValueType.NULL) {
                    columnRefMap.put(columnRefOperator, ConstantOperator.createNull(targetColumn.getType()));
                } else if (defaultValueType == Column.DefaultValueType.CONST) {
                    columnRefMap.put(columnRefOperator, ConstantOperator.createVarchar(
                            targetColumn.calculatedDefaultValue()));
                } else if (defaultValueType == Column.DefaultValueType.VARY) {
                    throw new SemanticException("Column:" + targetColumn.getName() + " has unsupported default value:"
                            + targetColumn.getDefaultExpr().getExpr());
                }
            } else {
                columnRefMap.put(outputColumns.get(columnIdx), outputColumns.get(columnIdx));
            }
        }
        return root.withNewRoot(new LogicalProjectOperator(new HashMap<>(columnRefMap)));
    }

    private OptExprBuilder castOutputColumnsTypeToTargetColumns(ColumnRefFactory columnRefFactory,
                                                                InsertStmt insertStatement,
                                                                List<ColumnRefOperator> outputColumns,
                                                                OptExprBuilder root) {
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = new HashMap<>();
        ScalarOperatorRewriter rewriter = new ScalarOperatorRewriter();
        List<ScalarOperatorRewriteRule> rewriteRules = Arrays.asList(new FoldConstantsRule());
        for (int columnIdx = 0; columnIdx < outputFullSchema.size(); ++columnIdx) {
            if (!outputFullSchema.get(columnIdx).getType().matchesType(outputColumns.get(columnIdx).getType())) {
                Column c = outputFullSchema.get(columnIdx);
                ColumnRefOperator k = columnRefFactory.create(c.getName(), c.getType(), c.isAllowNull());
                ScalarOperator castOperator = new CastOperator(outputFullSchema.get(columnIdx).getType(),
                        outputColumns.get(columnIdx), true);
                columnRefMap.put(k, rewriter.rewrite(castOperator, rewriteRules));
                outputColumns.set(columnIdx, k);
            } else {
                columnRefMap.put(outputColumns.get(columnIdx), outputColumns.get(columnIdx));
            }
        }
        return root.withNewRoot(new LogicalProjectOperator(new HashMap<>(columnRefMap)));
    }

    /**
     * OlapTableSink may be executed in multiply fragment instances of different machines
     * For non-duplicate key types, we must guarantee that the orders of the same key are
     * exactly the same. In order to achieve this goal, we can perform shuffle before TableSink
     * so that the same key will be sent to the same fragment instance
     */
    private PhysicalPropertySet createPhysicalPropertySet(InsertStmt insertStmt,
                                                          List<ColumnRefOperator> outputColumns,
                                                          SessionVariable session) {
        QueryRelation queryRelation = insertStmt.getQueryStatement().getQueryRelation();
        if ((queryRelation instanceof SelectRelation && queryRelation.hasLimit())) {
            DistributionProperty distributionProperty = DistributionProperty
                    .createProperty(new GatherDistributionSpec());
            return new PhysicalPropertySet(distributionProperty);
        }

        Table targetTable = insertStmt.getTargetTable();
        if (targetTable instanceof IcebergTable) {
            IcebergTable icebergTable = (IcebergTable) targetTable;
            SortOrder sortOrder = icebergTable.getNativeTable().sortOrder();

            if (sortOrder.isUnsorted()) {
                return new PhysicalPropertySet();
            } else {
                List<SortField> sortFields = sortOrder.fields();
                List<Ordering> orderings = new ArrayList<>();
                List<Integer> sortKeyIndexes = icebergTable.getSortKeyIndexes();
                for (int index : sortKeyIndexes) {
                    ColumnRefOperator columnRef = outputColumns.get(index);
                    SortField sortField = sortFields.get(sortKeyIndexes.indexOf(index));
                    boolean isAsc = sortField.direction() == SortDirection.ASC;
                    boolean isNullFirst = sortField.nullOrder() == NullOrder.NULLS_FIRST;
                    Ordering ordering = new Ordering(columnRef, isAsc, isNullFirst);
                    orderings.add(ordering);
                }
                SortProperty sortProperty = SortProperty.createProperty(orderings);
                return new PhysicalPropertySet(sortProperty);
            }
        }

        if (targetTable instanceof TableFunctionTable) {
            TableFunctionTable table = (TableFunctionTable) targetTable;
            if (table.isWriteSingleFile()) {
                return new PhysicalPropertySet(DistributionProperty
                        .createProperty(new GatherDistributionSpec()));
            }

            if (session.isEnableConnectorSinkGlobalShuffle()) {
                // use random shuffle for unpartitioned table
                if (table.getPartitionColumnNames().isEmpty()) {
                    return new PhysicalPropertySet(DistributionProperty
                            .createProperty(new RoundRobinDistributionSpec()));
                } else { // use hash shuffle for partitioned table
                    List<Integer> partitionColumnIDs = table.getPartitionColumnIDs().stream()
                            .map(x -> outputColumns.get(x).getId()).collect(Collectors.toList());
                    HashDistributionDesc desc = new HashDistributionDesc(partitionColumnIDs,
                            HashDistributionDesc.SourceType.SHUFFLE_AGG);
                    return new PhysicalPropertySet(DistributionProperty
                            .createProperty(DistributionSpec.createHashDistributionSpec(desc)));
                }
            }

            // no global shuffle
            return PhysicalPropertySet.EMPTY;
        }

        if (!(targetTable instanceof OlapTable)) {
            return new PhysicalPropertySet();
        }

        OlapTable table = (OlapTable) targetTable;

        if (KeysType.DUP_KEYS.equals(table.getKeysType())) {
            return new PhysicalPropertySet();
        }

        // No extra distribution property is needed if replication num is 1
        if (!enableSingleReplicationShuffle && table.getDefaultReplicationNum() <= 1) {
            return new PhysicalPropertySet();
        }

        if (table.enableReplicatedStorage()) {
            return new PhysicalPropertySet();
        }

        List<Column> columns = outputFullSchema;
        Preconditions.checkState(columns.size() == outputColumns.size(),
                "outputColumn's size must equal with table's column size");

        List<Column> keyColumns = table.getKeyColumnsByIndexId(table.getBaseIndexId());
        List<Integer> keyColumnIds = Lists.newArrayList();
        keyColumns.forEach(column -> {
            int index = columns.indexOf(column);
            Preconditions.checkState(index >= 0);
            keyColumnIds.add(outputColumns.get(index).getId());
        });

        HashDistributionDesc desc =
                new HashDistributionDesc(keyColumnIds, HashDistributionDesc.SourceType.SHUFFLE_AGG);
        DistributionSpec spec = DistributionSpec.createHashDistributionSpec(desc);
        DistributionProperty property = DistributionProperty.createProperty(spec);

        if (Config.eliminate_shuffle_load_by_replicated_storage) {
            forceReplicatedStorage = true;
            return new PhysicalPropertySet();
        }

        shuffleServiceEnable = true;

        return new PhysicalPropertySet(property);
    }

    private OptExprBuilder fillKeyPartitionsColumn(ColumnRefFactory columnRefFactory,
                                                   InsertStmt insertStatement, List<ColumnRefOperator> outputColumns,
                                                   OptExprBuilder root) {
        Table targetTable = insertStatement.getTargetTable();
        LogicalProjectOperator projectOperator = (LogicalProjectOperator) root.getRoot().getOp();
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = projectOperator.getColumnRefMap();
        List<String> partitionColNames = insertStatement.getTargetPartitionNames().getPartitionColNames();
        List<Expr> partitionColValues = insertStatement.getTargetPartitionNames().getPartitionColValues();
        List<String> tablePartitionColumnNames = targetTable.getPartitionColumnNames();

        for (Column column : targetTable.getFullSchema()) {
            String columnName = column.getName();
            if (tablePartitionColumnNames.contains(columnName)) {
                int index = partitionColNames.indexOf(columnName);
                LiteralExpr expr = (LiteralExpr) partitionColValues.get(index);
                Type type = expr.isConstantNull() ? Type.NULL : column.getType();
                ScalarOperator scalarOperator =
                        ConstantOperator.createObject(expr.getRealObjectValue(), type);
                ColumnRefOperator col = columnRefFactory
                        .create(scalarOperator, scalarOperator.getType(), scalarOperator.isNullable());
                outputColumns.add(col);
                columnRefMap.put(col, scalarOperator);
            }
        }
        return root.withNewRoot(new LogicalProjectOperator(new HashMap<>(columnRefMap)));
    }

    private boolean needToSkip(InsertStmt stmt, int columnIdx) {
        Table targetTable = stmt.getTargetTable();
        boolean skip = false;
        if (stmt.isSpecifyKeyPartition()) {
            if (targetTable.isIcebergTable()) {
                return ((IcebergTable) targetTable).partitionColumnIndexes().contains(columnIdx);
            } else if (targetTable.isHiveTable()) {
                return columnIdx >= targetTable.getFullSchema().size() - targetTable.getPartitionColumnNames().size();
            }
        }

        return skip;
    }

    private boolean checkPartitionInsertValid(Table targetTable) {
        if (targetTable instanceof IcebergTable) {
            IcebergTable icebergTable = (IcebergTable) targetTable;
            if (icebergTable.isPartitioned()) {
                PartitionSpec partitionSpec = icebergTable.getNativeTable().spec();
                boolean isInvalid = partitionSpec.fields().stream().anyMatch(field -> !field.transform().isIdentity());
                if (isInvalid) {
                    throw new SemanticException("Staitc insert into Iceberg table %s is not supported" + 
                            " for not partitioned by identity transform", icebergTable.getName());
                }
            }
        }

        return true;
    }

    private boolean isKeyPartitionStaticInsert(InsertStmt insertStmt, QueryRelation queryRelation) {
        Table targetTable = insertStmt.getTargetTable();

        if (!(targetTable.isHiveTable() || targetTable.isIcebergTable())) {
            return false;
        }

        if (targetTable.isUnPartitioned()) {
            return false;
        }

        if (insertStmt.isSpecifyKeyPartition()) {
            checkPartitionInsertValid(targetTable);
            return true;
        }

        if (!(queryRelation instanceof SelectRelation)) {
            return false;
        }

        SelectRelation selectRelation = (SelectRelation) queryRelation;
        List<SelectListItem> listItems = selectRelation.getSelectList().getItems();

        for (SelectListItem item : listItems) {
            if (item.isStar()) {
                return false;
            }
        }

        List<String> targetColumnNames;
        if (insertStmt.getTargetColumnNames() == null) {
            targetColumnNames = targetTable.getFullSchema().stream()
                    .map(Column::getName).collect(Collectors.toList());
        } else {
            targetColumnNames = Lists.newArrayList(insertStmt.getTargetColumnNames());
        }

        for (int i = 0; i < targetColumnNames.size(); i++) {
            String columnName = targetColumnNames.get(i);
            if (targetTable.getPartitionColumnNames().contains(columnName)) {
                Expr expr = listItems.get(i).getExpr();
                if (!expr.isConstant()) {
                    return false;
                }
            }
        }
        checkPartitionInsertValid(targetTable);
        return true;
    }
}
