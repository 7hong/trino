/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.cost.PlanNodeStatsEstimate;
import io.trino.cost.SymbolStatsEstimate;
import io.trino.cost.TaskCountEstimator;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Reference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.PlanNodeId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static io.trino.SystemSessionProperties.DISTINCT_AGGREGATIONS_STRATEGY;
import static io.trino.SystemSessionProperties.TASK_CONCURRENCY;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.Comparison.Operator.GREATER_THAN;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregation;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregationFunction;
import static io.trino.sql.planner.assertions.PlanMatchPattern.globalAggregation;
import static io.trino.sql.planner.assertions.PlanMatchPattern.markDistinct;
import static io.trino.sql.planner.assertions.PlanMatchPattern.singleGroupingSet;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.AggregationNode.Step.SINGLE;
import static io.trino.type.UnknownType.UNKNOWN;

public class TestMultipleDistinctAggregationToMarkDistinct
        extends BaseRuleTest
{
    private static final int NODES_COUNT = 4;
    private static final TaskCountEstimator TASK_COUNT_ESTIMATOR = new TaskCountEstimator(() -> NODES_COUNT);

    @Test
    public void testNoDistinct()
    {
        tester().assertThat(new SingleDistinctAggregationToGroupBy())
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", ImmutableList.of(new Reference(BIGINT, "input1"))), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("count", ImmutableList.of(new Reference(BIGINT, "input2"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(
                                        p.symbol("input1", BIGINT),
                                        p.symbol("input2", BIGINT)))))
                .doesNotFire();
    }

    @Test
    public void testSingleDistinct()
    {
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input1"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(
                                        p.symbol("input1", BIGINT),
                                        p.symbol("input2", BIGINT)))))
                .doesNotFire();
    }

    @Test
    public void testMultipleAggregations()
    {
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("sum", true, ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(p.symbol("input", BIGINT)))))
                .doesNotFire();
    }

    @Test
    public void testDistinctWithFilter()
    {
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(
                                p.symbol("output1"),
                                PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input1")), new Symbol(UNKNOWN, "filter1")), ImmutableList.of(BIGINT))
                        .addAggregation(
                                p.symbol("output2"),
                                PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input2")), new Symbol(UNKNOWN, "filter2")), ImmutableList.of(BIGINT))
                        .source(
                                p.project(
                                        Assignments.builder()
                                                .putIdentity(p.symbol("input1", BIGINT))
                                                .putIdentity(p.symbol("input2", BIGINT))
                                                .put(p.symbol("filter1", BOOLEAN), new Comparison(GREATER_THAN, new Reference(INTEGER, "input2"), new Constant(INTEGER, 0L)))
                                                .put(p.symbol("filter2", BOOLEAN), new Comparison(GREATER_THAN, new Reference(INTEGER, "input1"), new Constant(INTEGER, 0L)))
                                                .build(),
                                        p.values(
                                                p.symbol("input1", BIGINT),
                                                p.symbol("input2", BIGINT))))))
                .doesNotFire();

        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input1")), new Symbol(UNKNOWN, "filter1")), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input2"))), ImmutableList.of(BIGINT))
                        .source(
                                p.project(
                                        Assignments.builder()
                                                .putIdentity(p.symbol("input1", BIGINT))
                                                .putIdentity(p.symbol("input2", BIGINT))
                                                .put(p.symbol("filter1", BOOLEAN), new Comparison(GREATER_THAN, new Reference(INTEGER, "input2"), new Constant(INTEGER, 0L)))
                                                .put(p.symbol("filter2", BOOLEAN), new Comparison(GREATER_THAN, new Reference(INTEGER, "input1"), new Constant(INTEGER, 0L)))
                                                .build(),
                                        p.values(
                                                p.symbol("input1", BIGINT),
                                                p.symbol("input2", BIGINT))))))
                .doesNotFire();
    }

    @Test
    public void testGlobalAggregation()
    {
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .on(p -> p.aggregation(builder -> builder
                        .globalGrouping()
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input1"))), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input2"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(p.symbol("input1", BIGINT), p.symbol("input2", BIGINT)))))
                .matches(aggregation(
                        globalAggregation(),
                        ImmutableMap.of(
                                Optional.of("output1"), aggregationFunction("count", ImmutableList.of("input1")),
                                Optional.of("output2"), aggregationFunction("count", ImmutableList.of("input2"))),
                        ImmutableList.of(),
                        ImmutableList.of("mark_input1", "mark_input2"),
                        Optional.empty(),
                        SINGLE,
                        markDistinct(
                                "mark_input2",
                                ImmutableList.of("input2"),
                                markDistinct(
                                        "mark_input1",
                                        ImmutableList.of("input1"),
                                        values(ImmutableMap.of("input1", 0, "input2", 1))))));
    }

    @Test
    public void testAggregationNDV()
    {
        PlanNodeId aggregationSourceId = new PlanNodeId("aggregationSourceId");
        Symbol key = new Symbol(BIGINT, "key");
        Function<PlanBuilder, PlanNode> plan = p -> p.aggregation(builder -> builder
                .singleGroupingSet(p.symbol(key.name(), BIGINT))
                .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("sum", ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                .source(
                        p.values(aggregationSourceId, p.symbol("input", BIGINT), p.symbol(key.name(), BIGINT))));
        PlanMatchPattern expectedMarkDistinct = aggregation(
                singleGroupingSet("key"),
                ImmutableMap.of(
                        Optional.of("output1"), aggregationFunction("count", ImmutableList.of("input")),
                        Optional.of("output2"), aggregationFunction("sum", ImmutableList.of("input"))),
                ImmutableList.of(),
                ImmutableList.of("mark_input"),
                Optional.empty(),
                SINGLE,
                markDistinct(
                        "mark_input",
                        ImmutableList.of("input", "key"),
                        values(ImmutableMap.of("input", 0, "key", 1))));

        int clusterThreadCount = NODES_COUNT * tester().getSession().getSystemProperty(TASK_CONCURRENCY, Integer.class);

        // small NDV
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(
                                key, SymbolStatsEstimate.builder().setDistinctValuesCount(2 * clusterThreadCount).build())
                        .build())
                .on(plan)
                .matches(expectedMarkDistinct);

        // unknown estimate
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(
                                key, SymbolStatsEstimate.builder().setDistinctValuesCount(Double.NaN).build())
                        .build())
                .on(plan)
                .matches(expectedMarkDistinct);

        // medium NDV, distinct_aggregations_strategy mark_distinct
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(
                                key, SymbolStatsEstimate.builder().setDistinctValuesCount(50 * clusterThreadCount).build())
                        .build())
                .setSystemProperty(DISTINCT_AGGREGATIONS_STRATEGY, "mark_distinct")
                .on(plan)
                .matches(expectedMarkDistinct);

        // medium NDV
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(
                                key, SymbolStatsEstimate.builder().setDistinctValuesCount(50 * clusterThreadCount).build())
                        .build())
                .on(plan)
                .doesNotFire();

        // medium NDV, distinct_aggregations_strategy pre_aggregate, but the plan has multiple distinct aggregations
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .setSystemProperty(DISTINCT_AGGREGATIONS_STRATEGY, "pre_aggregate")
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(key, SymbolStatsEstimate.builder().setDistinctValuesCount(50 * clusterThreadCount).build()).build())
                .on(p -> p.aggregation(builder -> builder
                        .singleGroupingSet(p.symbol(key.name(), BIGINT))
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input1"))), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input2"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(aggregationSourceId, p.symbol("input1", BIGINT), p.symbol("input2", BIGINT), p.symbol("key", BIGINT)))))
                .doesNotFire();

        // big NDV
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder()
                        .addSymbolStatistics(key, SymbolStatsEstimate.builder().setDistinctValuesCount(1000 * clusterThreadCount).build()).build()).on(plan)
                .doesNotFire();

        // big NDV, distinct_aggregations_strategy = mark_distinct
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .setSystemProperty(DISTINCT_AGGREGATIONS_STRATEGY, "mark_distinct")
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder().setOutputRowCount(1000 * clusterThreadCount).build())
                .on(plan)
                .matches(expectedMarkDistinct);
        // small NDV, distinct_aggregations_strategy != mark_distinct
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .setSystemProperty(DISTINCT_AGGREGATIONS_STRATEGY, "single_step")
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder().setOutputRowCount(2 * clusterThreadCount).build())
                .on(plan)
                .doesNotFire();

        // big NDV but on multiple grouping keys
        Symbol key1 = new Symbol(BIGINT, "key1");
        Symbol key2 = new Symbol(BIGINT, "key2");
        tester().assertThat(new MultipleDistinctAggregationToMarkDistinct(TASK_COUNT_ESTIMATOR))
                .overrideStats(aggregationSourceId.toString(), PlanNodeStatsEstimate.builder().setOutputRowCount(1000 * clusterThreadCount)
                        .addSymbolStatistics(key1, SymbolStatsEstimate.builder().setDistinctValuesCount(1000 * clusterThreadCount).build())
                        .addSymbolStatistics(key2, SymbolStatsEstimate.builder().setDistinctValuesCount(10).build())
                        .build())
                .on(p -> p.aggregation(builder -> builder
                        .singleGroupingSet(p.symbol(key1.name(), BIGINT), p.symbol(key2.name(), BIGINT))
                        .addAggregation(p.symbol("output1", BIGINT), PlanBuilder.aggregation("count", true, ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                        .addAggregation(p.symbol("output2", BIGINT), PlanBuilder.aggregation("sum", ImmutableList.of(new Reference(BIGINT, "input"))), ImmutableList.of(BIGINT))
                        .source(
                                p.values(aggregationSourceId, p.symbol("input", BIGINT), p.symbol("key1", BIGINT), p.symbol("key2", BIGINT)))))
                .doesNotFire();
    }
}
