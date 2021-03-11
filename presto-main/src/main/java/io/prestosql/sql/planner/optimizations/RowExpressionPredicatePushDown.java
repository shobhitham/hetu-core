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
package io.prestosql.sql.planner.optimizations;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.expressions.LogicalRowExpressions;
import io.prestosql.expressions.RowExpressionNodeInliner;
import io.prestosql.metadata.Metadata;
import io.prestosql.operator.scalar.TryFunction;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.function.Signature;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.Assignments;
import io.prestosql.spi.plan.CTEScanNode;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.GroupIdNode;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.MarkDistinctNode;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TableScanNode;
import io.prestosql.spi.plan.UnionNode;
import io.prestosql.spi.plan.WindowNode;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.ConstantExpression;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.spi.sql.RowExpressionUtils;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.RowExpressionEqualityInference;
import io.prestosql.sql.planner.RowExpressionInterpreter;
import io.prestosql.sql.planner.RowExpressionPredicateExtractor;
import io.prestosql.sql.planner.RowExpressionVariableInliner;
import io.prestosql.sql.planner.SymbolUtils;
import io.prestosql.sql.planner.TypeAnalyzer;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.VariablesExtractor;
import io.prestosql.sql.planner.plan.AssignUniqueId;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.SampleNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.UnnestNode;
import io.prestosql.sql.relational.Expressions;
import io.prestosql.sql.relational.RowExpressionDeterminismEvaluator;
import io.prestosql.sql.relational.RowExpressionDomainTranslator;
import io.prestosql.sql.relational.RowExpressionOptimizer;
import io.prestosql.type.InternalTypeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.filter;
import static io.prestosql.SystemSessionProperties.isEnableDynamicFiltering;
import static io.prestosql.spi.function.OperatorType.EQUAL;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.prestosql.spi.function.Signature.internalOperator;
import static io.prestosql.spi.function.Signature.unmangleOperator;
import static io.prestosql.spi.plan.JoinNode.DistributionType.PARTITIONED;
import static io.prestosql.spi.plan.JoinNode.DistributionType.REPLICATED;
import static io.prestosql.spi.plan.JoinNode.Type.FULL;
import static io.prestosql.spi.plan.JoinNode.Type.INNER;
import static io.prestosql.spi.plan.JoinNode.Type.LEFT;
import static io.prestosql.spi.plan.JoinNode.Type.RIGHT;
import static io.prestosql.spi.sql.RowExpressionUtils.FALSE_CONSTANT;
import static io.prestosql.spi.sql.RowExpressionUtils.TRUE_CONSTANT;
import static io.prestosql.spi.sql.RowExpressionUtils.extractConjuncts;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.sql.DynamicFilters.createDynamicFilterRowExpression;
import static io.prestosql.sql.DynamicFilters.extractDynamicFilters;
import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.planner.VariableReferenceSymbolConverter.toSymbol;
import static io.prestosql.sql.planner.VariableReferenceSymbolConverter.toVariableReference;
import static io.prestosql.sql.planner.VariableReferenceSymbolConverter.toVariableReferenceMap;
import static io.prestosql.sql.planner.VariableReferenceSymbolConverter.toVariableReferences;
import static io.prestosql.sql.planner.plan.AssignmentUtils.identityAssignments;
import static io.prestosql.sql.relational.Expressions.call;
import static io.prestosql.sql.relational.Expressions.constant;
import static io.prestosql.sql.relational.Expressions.constantNull;
import static io.prestosql.sql.relational.Expressions.uniqueSubExpressions;
import static io.prestosql.sql.tree.BooleanLiteral.FALSE_LITERAL;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

public class RowExpressionPredicatePushDown
        implements PlanOptimizer
{
    private final Metadata metadata;
    private final TypeAnalyzer typeAnalyzer;
    private final boolean useTableProperties;
    private final boolean dynamicFiltering;

    public RowExpressionPredicatePushDown(Metadata metadata, TypeAnalyzer typeAnalyzer, boolean useTableProperties, boolean dynamicFiltering)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
        this.useTableProperties = useTableProperties;
        this.dynamicFiltering = dynamicFiltering;
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(idAllocator, "idAllocator is null");

        RowExpressionPredicateExtractor predicateExtractor = new RowExpressionPredicateExtractor(new RowExpressionDomainTranslator(metadata), metadata, planSymbolAllocator, useTableProperties);

        return SimplePlanRewriter.rewriteWith(
                new Rewriter(planSymbolAllocator, idAllocator, metadata, predicateExtractor, typeAnalyzer, session, dynamicFiltering),
                plan,
                TRUE_CONSTANT);
    }

    private static class Rewriter
            extends SimplePlanRewriter<RowExpression>
    {
        private final PlanSymbolAllocator planSymbolAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final RowExpressionPredicateExtractor effectivePredicateExtractor;
        private final Session session;
        private final ExpressionEquivalence expressionEquivalence;
        private final RowExpressionDeterminismEvaluator determinismEvaluator;
        private final LogicalRowExpressions logicalRowExpressions;
        private final TypeManager typeManager;
        private final boolean dynamicFiltering;

        private Rewriter(
                PlanSymbolAllocator planSymbolAllocator,
                PlanNodeIdAllocator idAllocator,
                Metadata metadata,
                RowExpressionPredicateExtractor effectivePredicateExtractor,
                TypeAnalyzer typeAnalyzer,
                Session session,
                boolean dynamicFiltering)
        {
            this.planSymbolAllocator = requireNonNull(planSymbolAllocator, "variableAllocator is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.effectivePredicateExtractor = requireNonNull(effectivePredicateExtractor, "effectivePredicateExtractor is null");
            this.session = requireNonNull(session, "session is null");
            this.expressionEquivalence = new ExpressionEquivalence(metadata, typeAnalyzer);
            this.determinismEvaluator = new RowExpressionDeterminismEvaluator(metadata);
            this.logicalRowExpressions = new LogicalRowExpressions(determinismEvaluator);
            this.typeManager = new InternalTypeManager(metadata);
            this.dynamicFiltering = dynamicFiltering;
        }

        @Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<RowExpression> context)
        {
            PlanNode rewrittenNode = context.defaultRewrite(node, TRUE_CONSTANT);
            if (!context.get().equals(TRUE_CONSTANT)) {
                // Drop in a FilterNode b/c we cannot push our predicate down any further
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, context.get());
            }
            return rewrittenNode;
        }

        @Override
        public PlanNode visitCTEScan(CTEScanNode node, RewriteContext<RowExpression> context)
        {
            if (dynamicFiltering && extractDynamicFilters(context.get()).getStaticConjuncts().size() == 0) {
                //Currently we pushdown only if dynamic filter expression there.
                return context.defaultRewrite(node, context.get());
            }
            else {
                return visitPlan(node, context);
            }
        }

        @Override
        public PlanNode visitExchange(ExchangeNode node, RewriteContext<RowExpression> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                Map<VariableReferenceExpression, VariableReferenceExpression> outputsToInputs = new HashMap<>();
                for (int index = 0; index < node.getInputs().get(i).size(); index++) {
                    outputsToInputs.put(
                            toVariableReference(node.getOutputSymbols().get(index), planSymbolAllocator.getTypes()),
                            toVariableReference(node.getInputs().get(i).get(index), planSymbolAllocator.getTypes()));
                }

                RowExpression sourcePredicate = RowExpressionVariableInliner.inlineVariables(outputsToInputs, context.get());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source, sourcePredicate);
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                return new ExchangeNode(
                        node.getId(),
                        node.getType(),
                        node.getScope(),
                        node.getPartitioningScheme(),
                        builder.build(),
                        node.getInputs(),
                        node.getOrderingScheme());
            }

            return node;
        }

        @Override
        public PlanNode visitWindow(WindowNode node, RewriteContext<RowExpression> context)
        {
            // TODO: This could be broader. We can push down conjucts if they are constant for all rows in a window partition.
            // The simplest way to guarantee this is if the conjucts are deterministic functions of the partitioning variables.
            // This can leave out cases where they're both functions of some set of common expressions and the partitioning
            // function is injective, but that's a rare case. The majority of window nodes are expected to be partitioned by
            // pre-projected variables.
            Predicate<RowExpression> isSupported = conjunct ->
                    determinismEvaluator.isDeterministic(conjunct) &&
                            VariablesExtractor.extractUnique(conjunct).stream().allMatch(node.getPartitionBy()::contains);

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(isSupported));

            PlanNode rewrittenNode = context.defaultRewrite(node, RowExpressionUtils.combineConjuncts(conjuncts.get(true)));

            if (!conjuncts.get(false).isEmpty()) {
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, RowExpressionUtils.combineConjuncts(conjuncts.get(false)));
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> deterministicVariables = node.getAssignments().entrySet().stream()
                    .filter(entry -> determinismEvaluator.isDeterministic(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .map(symbol -> toVariableReference(symbol, planSymbolAllocator.getTypes()))
                    .collect(Collectors.toSet());

            Predicate<RowExpression> deterministic = conjunct -> deterministicVariables.containsAll(VariablesExtractor.extractUnique(conjunct));

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(deterministic));

            // Push down conjuncts from the inherited predicate that only depend on deterministic assignments with
            // certain limitations.
            List<RowExpression> deterministicConjuncts = conjuncts.get(true);

            // We partition the expressions in the deterministicConjuncts into two lists, and only inline the
            // expressions that are in the inlining targets list.
            Map<Boolean, List<RowExpression>> inlineConjuncts = deterministicConjuncts.stream()
                    .collect(Collectors.partitioningBy(expression -> isInliningCandidate(expression, node)));

            List<RowExpression> inlinedDeterministicConjuncts = inlineConjuncts.get(true).stream()
                    .map(entry -> RowExpressionVariableInliner.inlineVariables(toVariableReferenceMap(node.getAssignments().getMap(), planSymbolAllocator.getTypes()), entry))
                    .collect(Collectors.toList());

            PlanNode rewrittenNode = context.defaultRewrite(node, RowExpressionUtils.combineConjuncts(inlinedDeterministicConjuncts));

            // All deterministic conjuncts that contains non-inlining targets, and non-deterministic conjuncts,
            // if any, will be in the filter node.
            List<RowExpression> nonInliningConjuncts = inlineConjuncts.get(false);
            nonInliningConjuncts.addAll(conjuncts.get(false));

            if (!nonInliningConjuncts.isEmpty()) {
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, RowExpressionUtils.combineConjuncts(nonInliningConjuncts));
            }

            return rewrittenNode;
        }

        private boolean isInliningCandidate(RowExpression expression, ProjectNode node)
        {
            // TryExpressions should not be pushed down. However they are now being handled as lambda
            // passed to a FunctionCall now and should not affect predicate push down. So we want to make
            // sure the conjuncts are not TryExpressions.

            verify(uniqueSubExpressions(expression)
                    .stream()
                    .noneMatch(subExpression -> subExpression instanceof CallExpression &&
                            (((CallExpression) subExpression).getSignature().getName()).equals(TryFunction.NAME)));

            // candidate symbols for inlining are
            //   1. references to simple constants
            //   2. references to complex expressions that appear only once
            // which come from the node, as opposed to an enclosing scope.
            Set<VariableReferenceExpression> childOutputSet = ImmutableSet.copyOf(toVariableReferences(node.getOutputSymbols(), planSymbolAllocator.getTypes()));
            Map<VariableReferenceExpression, Long> dependencies = VariablesExtractor.extractAll(expression).stream()
                    .filter(childOutputSet::contains)
                    .collect(Collectors.groupingBy(identity(), Collectors.counting()));

            return dependencies.entrySet().stream()
                    .allMatch(entry -> entry.getValue() == 1 || node.getAssignments().get(toSymbol(entry.getKey())) instanceof ConstantExpression);
        }

        @Override
        public PlanNode visitGroupId(GroupIdNode node, RewriteContext<RowExpression> context)
        {
            Map<VariableReferenceExpression, VariableReferenceExpression> commonGroupingVariableMapping = node.getGroupingColumns().entrySet().stream()
                    .filter(entry -> node.getCommonGroupingColumns().contains(entry.getKey()))
                    .collect(Collectors.toMap(entry -> toVariableReference(entry.getKey(), planSymbolAllocator.getTypes()), entry -> toVariableReference(entry.getValue(), planSymbolAllocator.getTypes())));

            Predicate<RowExpression> pushdownEligiblePredicate = conjunct -> VariablesExtractor.extractUnique(conjunct).stream()
                    .allMatch(commonGroupingVariableMapping.keySet()::contains);

            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream().collect(Collectors.partitioningBy(pushdownEligiblePredicate));

            // Push down conjuncts from the inherited predicate that apply to common grouping symbols
            PlanNode rewrittenNode = context.defaultRewrite(node, RowExpressionVariableInliner.inlineVariables(commonGroupingVariableMapping, RowExpressionUtils.combineConjuncts(conjuncts.get(true))));

            // All other conjuncts, if any, will be in the filter node.
            if (!conjuncts.get(false).isEmpty()) {
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, RowExpressionUtils.combineConjuncts(conjuncts.get(false)));
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitMarkDistinct(MarkDistinctNode node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> pushDownableVariables = ImmutableSet.copyOf(toVariableReferences(node.getDistinctSymbols(), planSymbolAllocator.getTypes()));
            Map<Boolean, List<RowExpression>> conjuncts = extractConjuncts(context.get()).stream()
                    .collect(Collectors.partitioningBy(conjunct -> pushDownableVariables.containsAll(VariablesExtractor.extractUnique(conjunct))));

            PlanNode rewrittenNode = context.defaultRewrite(node, RowExpressionUtils.combineConjuncts(conjuncts.get(true)));

            if (!conjuncts.get(false).isEmpty()) {
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, RowExpressionUtils.combineConjuncts(conjuncts.get(false)));
            }
            return rewrittenNode;
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<RowExpression> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<RowExpression> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                Map<VariableReferenceExpression, VariableReferenceExpression> sourceVariable = node.sourceSymbolMap(i).entrySet().stream()
                        .collect(Collectors.toMap(entry -> toVariableReference(entry.getKey(), planSymbolAllocator.getTypes()), entry -> toVariableReference(entry.getValue(), planSymbolAllocator.getTypes())));
                RowExpression sourcePredicate = RowExpressionVariableInliner.inlineVariables(sourceVariable, context.get());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source, sourcePredicate);
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                return new UnionNode(node.getId(), builder.build(), node.getSymbolMapping(), node.getOutputSymbols());
            }

            return node;
        }

        @Deprecated
        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<RowExpression> context)
        {
            PlanNode rewrittenPlan = context.rewrite(node.getSource(), RowExpressionUtils.combineConjuncts(node.getPredicate(), context.get()));
            if (!(rewrittenPlan instanceof FilterNode)) {
                return rewrittenPlan;
            }

            FilterNode rewrittenFilterNode = (FilterNode) rewrittenPlan;
            if (!areExpressionsEquivalent(rewrittenFilterNode.getPredicate(), node.getPredicate())
                    || node.getSource() != rewrittenFilterNode.getSource()) {
                return rewrittenPlan;
            }

            return node;
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            // See if we can rewrite outer joins in terms of a plain inner join
            node = tryNormalizeToOuterToInnerJoin(node, inheritedPredicate);

            RowExpression leftEffectivePredicate = effectivePredicateExtractor.extract(node.getLeft(), session);
            RowExpression rightEffectivePredicate = effectivePredicateExtractor.extract(node.getRight(), session);
            RowExpression joinPredicate = extractJoinPredicate(node);

            RowExpression leftPredicate;
            RowExpression rightPredicate;
            RowExpression postJoinPredicate;
            RowExpression newJoinPredicate;

            List<VariableReferenceExpression> nodeLeftOutput = toVariableReferences(node.getLeft().getOutputSymbols(), planSymbolAllocator.getTypes());
            List<VariableReferenceExpression> nodeRightOutput = toVariableReferences(node.getRight().getOutputSymbols(), planSymbolAllocator.getTypes());

            switch (node.getType()) {
                case INNER:
                    InnerJoinPushDownResult innerJoinPushDownResult = processInnerJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            nodeLeftOutput);
                    leftPredicate = innerJoinPushDownResult.getLeftPredicate();
                    rightPredicate = innerJoinPushDownResult.getRightPredicate();
                    postJoinPredicate = innerJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = innerJoinPushDownResult.getJoinPredicate();
                    break;
                case LEFT:
                    OuterJoinPushDownResult leftOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            nodeLeftOutput);
                    leftPredicate = leftOuterJoinPushDownResult.getOuterJoinPredicate();
                    rightPredicate = leftOuterJoinPushDownResult.getInnerJoinPredicate();
                    postJoinPredicate = leftOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = leftOuterJoinPushDownResult.getJoinPredicate();
                    break;
                case RIGHT:
                    OuterJoinPushDownResult rightOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            rightEffectivePredicate,
                            leftEffectivePredicate,
                            joinPredicate,
                            nodeRightOutput);
                    leftPredicate = rightOuterJoinPushDownResult.getInnerJoinPredicate();
                    rightPredicate = rightOuterJoinPushDownResult.getOuterJoinPredicate();
                    postJoinPredicate = rightOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = rightOuterJoinPushDownResult.getJoinPredicate();
                    break;
                case FULL:
                    leftPredicate = TRUE_CONSTANT;
                    rightPredicate = TRUE_CONSTANT;
                    postJoinPredicate = inheritedPredicate;
                    newJoinPredicate = joinPredicate;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
            }

            newJoinPredicate = simplifyExpression(newJoinPredicate);
            // TODO: find a better way to directly optimize FALSE LITERAL in join predicate
            if (newJoinPredicate.equals(FALSE_CONSTANT)) {
                newJoinPredicate = buildEqualsExpression(constant(0L, BIGINT), constant(1L, BIGINT));
            }

            PlanNode output = node;

            // Create identity projections for all existing symbols
            Assignments.Builder leftProjections = Assignments.builder()
                    .putAll(identityAssignments(planSymbolAllocator.getTypes(), node.getLeft().getOutputSymbols()));

            Assignments.Builder rightProjections = Assignments.builder()
                    .putAll(identityAssignments(planSymbolAllocator.getTypes(), node.getRight().getOutputSymbols()));

            // Create new projections for the new join clauses
            List<JoinNode.EquiJoinClause> equiJoinClauses = new ArrayList<>();
            ImmutableList.Builder<RowExpression> joinFilterBuilder = ImmutableList.builder();
            for (RowExpression conjunct : extractConjuncts(newJoinPredicate)) {
                if (joinEqualityExpression(nodeLeftOutput).test(conjunct)) {
                    boolean alignedComparison = Iterables.all(VariablesExtractor.extractUnique(getLeft(conjunct)), in(nodeLeftOutput));
                    RowExpression leftExpression = (alignedComparison) ? getLeft(conjunct) : getRight(conjunct);
                    RowExpression rightExpression = (alignedComparison) ? getRight(conjunct) : getLeft(conjunct);

                    VariableReferenceExpression leftVariable = variableForExpression(leftExpression);
                    if (!nodeLeftOutput.contains(leftVariable)) {
                        leftProjections.put(toSymbol(leftVariable), leftExpression);
                    }

                    VariableReferenceExpression rightVariable = variableForExpression(rightExpression);
                    if (!nodeRightOutput.contains(rightVariable)) {
                        rightProjections.put(toSymbol(rightVariable), rightExpression);
                    }

                    equiJoinClauses.add(new JoinNode.EquiJoinClause(toSymbol(leftVariable), toSymbol(rightVariable)));
                }
                else {
                    joinFilterBuilder.add(conjunct);
                }
            }

            Optional<RowExpression> newJoinFilter = Optional.of(RowExpressionUtils.combineConjuncts(joinFilterBuilder.build()));
            if (newJoinFilter.get() == TRUE_CONSTANT) {
                newJoinFilter = Optional.empty();
            }

            //extract expression to be pushed down to tablescan and leverage dynamic filter for filtering
            DynamicFiltersResult dynamicFiltersResult = createDynamicFilters(node, equiJoinClauses, newJoinFilter, session, idAllocator);
            Map<String, Symbol> dynamicFilters = dynamicFiltersResult.getDynamicFilters();

            //the result leftPredicate will have the dynamic filter predicate 'AND' to it.
            leftPredicate = RowExpressionUtils.combineConjuncts(leftPredicate, RowExpressionUtils.combineConjuncts(dynamicFiltersResult.getPredicates()));

            PlanNode leftSource;
            PlanNode rightSource;
            boolean equiJoinClausesUnmodified = ImmutableSet.copyOf(equiJoinClauses).equals(ImmutableSet.copyOf(node.getCriteria()));
            if (!equiJoinClausesUnmodified) {
                leftSource = context.rewrite(new ProjectNode(idAllocator.getNextId(), node.getLeft(), leftProjections.build()), leftPredicate);
                rightSource = context.rewrite(new ProjectNode(idAllocator.getNextId(), node.getRight(), rightProjections.build()), rightPredicate);
            }
            else {
                leftSource = context.rewrite(node.getLeft(), leftPredicate);
                rightSource = context.rewrite(node.getRight(), rightPredicate);
            }

            if (node.getType() == INNER && newJoinFilter.isPresent() && equiJoinClauses.isEmpty()) {
                // if we do not have any equi conjunct we do not pushdown non-equality condition into
                // inner join, so we plan execution as nested-loops-join followed by filter instead
                // hash join.
                // todo: remove the code when we have support for filter function in nested loop join
                postJoinPredicate = RowExpressionUtils.combineConjuncts(postJoinPredicate, newJoinFilter.get());
                newJoinFilter = Optional.empty();
            }

            boolean filtersEquivalent =
                    newJoinFilter.isPresent() == node.getFilter().isPresent() &&
                            (!newJoinFilter.isPresent() || areExpressionsEquivalent(newJoinFilter.get(), node.getFilter().get()));

            if (leftSource != node.getLeft() ||
                    rightSource != node.getRight() ||
                    !filtersEquivalent ||
                    !dynamicFilters.equals(node.getDynamicFilters()) ||
                    !equiJoinClausesUnmodified) {
                leftSource = new ProjectNode(idAllocator.getNextId(), leftSource, leftProjections.build());
                rightSource = new ProjectNode(idAllocator.getNextId(), rightSource, rightProjections.build());

                // if the distribution type is already set, make sure that changes from PredicatePushDown
                // don't make the join node invalid.
                Optional<JoinNode.DistributionType> distributionType = node.getDistributionType();
                if (node.getDistributionType().isPresent()) {
                    if (node.getType().mustPartition()) {
                        distributionType = Optional.of(PARTITIONED);
                    }
                    if (node.getType().mustReplicate(equiJoinClauses)) {
                        distributionType = Optional.of(REPLICATED);
                    }
                }

                output = new JoinNode(
                        node.getId(),
                        node.getType(),
                        leftSource,
                        rightSource,
                        equiJoinClauses,
                        ImmutableList.<Symbol>builder()
                                .addAll(leftSource.getOutputSymbols())
                                .addAll(rightSource.getOutputSymbols())
                                .build(),
                        newJoinFilter,
                        node.getLeftHashSymbol(),
                        node.getRightHashSymbol(),
                        distributionType,
                        node.isSpillable(),
                        dynamicFilters);
            }

            if (!postJoinPredicate.equals(TRUE_CONSTANT)) {
                output = new FilterNode(idAllocator.getNextId(), output, postJoinPredicate);
            }

            if (!node.getOutputSymbols().equals(output.getOutputSymbols())) {
                output = new ProjectNode(idAllocator.getNextId(), output, identityAssignments(planSymbolAllocator.getTypes(), node.getOutputSymbols()));
            }

            return output;
        }

        private DynamicFiltersResult createDynamicFilters(JoinNode node, List<JoinNode.EquiJoinClause> equiJoinClauses,
                                                          Optional<RowExpression> newJoinFilter, Session session,
                                                          PlanNodeIdAllocator idAllocator)
        {
            Map<String, Symbol> dynamicFilters = ImmutableMap.of();
            List<RowExpression> predicates = ImmutableList.of();
            if ((node.getType() == INNER || node.getType() == RIGHT) && isEnableDynamicFiltering(session) && dynamicFiltering) {
                // New equiJoinClauses could potentially not contain symbols used in current dynamic filters.
                // Since we use PredicatePushdown to push dynamic filters themselves,
                // instead of separate ApplyDynamicFilters rule we derive dynamic filters within PredicatePushdown itself.
                // Even if equiJoinClauses.equals(node.getCriteria), current dynamic filters may not match equiJoinClauses
                ImmutableMap.Builder<String, Symbol> dynamicFiltersBuilder = ImmutableMap.builder();
                ImmutableList.Builder<RowExpression> predicatesBuilder = ImmutableList.builder();
                for (JoinNode.EquiJoinClause clause : equiJoinClauses) {
                    Symbol probeSymbol = clause.getLeft();
                    Symbol buildSymbol = clause.getRight();
                    String id = idAllocator.getNextId().toString();
                    predicatesBuilder.add(createDynamicFilterRowExpression(metadata, typeManager, id, planSymbolAllocator.getTypes().get(probeSymbol), toSymbolReference(probeSymbol), Optional.empty()));
                    dynamicFiltersBuilder.put(id, buildSymbol);
                }
                if (newJoinFilter.isPresent() && (!newJoinFilter.get().equals(TRUE_LITERAL) || newJoinFilter.get().equals(FALSE_LITERAL))) {
                    RowExpression joinFilter = newJoinFilter.get();
                    List<RowExpression> expressions = RowExpressionUtils.extractConjuncts(joinFilter);
                    Set<Symbol> usedSymbols = new HashSet<>();
                    for (RowExpression expression : expressions) {
                        if (expression instanceof CallExpression) {
                            CallExpression call = (CallExpression) expression;
                            String name = call.getSignature().getName();
                            if (name.contains("$operator$") && isDynamicFilterComparisonOperator(name)) {
                                if (call.getArguments().stream().allMatch(VariableReferenceExpression.class::isInstance)) {
                                    if (call.getArguments().get(0).getType() != BIGINT) {
                                        continue;
                                    }

                                    Symbol probeSymbol = null;
                                    Symbol buildSymbol = null;
                                    Symbol left = new Symbol(((VariableReferenceExpression) call.getArguments().get(0)).getName());
                                    Symbol right = new Symbol(((VariableReferenceExpression) call.getArguments().get(1)).getName());

                                    Optional<RowExpression> filter = Optional.empty();
                                    if (node.getLeft().getOutputSymbols().contains(left) && node.getRight().getOutputSymbols().contains(right)) {
                                        probeSymbol = left;
                                        buildSymbol = right;
                                        if (unmangleOperator(name) != OperatorType.EQUAL) {
                                            //To skip dependency checker, both arguments are same.
                                            Signature signature = internalOperator(unmangleOperator(name),
                                                    call.getSignature().getReturnType(),
                                                    call.getSignature().getArgumentTypes().get(0),
                                                    call.getSignature().getArgumentTypes().get(1));
                                            List<RowExpression> arguments = new ArrayList<>();
                                            arguments.add(call.getArguments().get(0));
                                            arguments.add(call.getArguments().get(1));

                                            filter = Optional.of(new CallExpression(signature, call.getType(), arguments, Optional.empty()));
                                        }
                                    }
                                    else if (node.getRight().getOutputSymbols().contains(left) && node.getLeft().getOutputSymbols().contains(right)) {
                                        probeSymbol = right;
                                        buildSymbol = left;

                                        filter = Optional.of(RowExpressionUtils.flip(call));
                                    }

                                    if (probeSymbol == null || usedSymbols.contains(buildSymbol) || usedSymbols.contains(probeSymbol)) {
                                        continue;
                                    }

                                    usedSymbols.add(buildSymbol);
                                    usedSymbols.add(probeSymbol);

                                    String id = idAllocator.getNextId().toString();
                                    predicatesBuilder.add(createDynamicFilterRowExpression(metadata, typeManager, id, planSymbolAllocator.getTypes().get(probeSymbol), toSymbolReference(probeSymbol), filter));
                                    dynamicFiltersBuilder.put(id, buildSymbol);
                                }
                            }
                        }
                    }
                }
                dynamicFilters = dynamicFiltersBuilder.build();
                predicates = predicatesBuilder.build();
            }
            return new DynamicFiltersResult(dynamicFilters, predicates);
        }

        private static class DynamicFiltersResult
        {
            private final Map<String, Symbol> dynamicFilters;
            private final List<RowExpression> predicates;

            public DynamicFiltersResult(Map<String, Symbol> dynamicFilters, List<RowExpression> predicates)
            {
                this.dynamicFilters = dynamicFilters;
                this.predicates = predicates;
            }

            public Map<String, Symbol> getDynamicFilters()
            {
                return dynamicFilters;
            }

            public List<RowExpression> getPredicates()
            {
                return predicates;
            }
        }

        private static RowExpression getLeft(RowExpression expression)
        {
            checkArgument(expression instanceof CallExpression && ((CallExpression) expression).getArguments().size() == 2, "must be binary call expression");
            return ((CallExpression) expression).getArguments().get(0);
        }

        private static RowExpression getRight(RowExpression expression)
        {
            checkArgument(expression instanceof CallExpression && ((CallExpression) expression).getArguments().size() == 2, "must be binary call expression");
            return ((CallExpression) expression).getArguments().get(1);
        }

        @Override
        public PlanNode visitSpatialJoin(SpatialJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            List<VariableReferenceExpression> nodeLeftOutput = toVariableReferences(node.getLeft().getOutputSymbols(), planSymbolAllocator.getTypes());
            List<VariableReferenceExpression> nodeRightOutput = toVariableReferences(node.getRight().getOutputSymbols(), planSymbolAllocator.getTypes());

            // See if we can rewrite left join in terms of a plain inner join
            if (node.getType() == SpatialJoinNode.Type.LEFT && canConvertOuterToInner(nodeRightOutput, inheritedPredicate)) {
                node = new SpatialJoinNode(
                        node.getId(),
                        SpatialJoinNode.Type.INNER,
                        node.getLeft(),
                        node.getRight(),
                        node.getOutputSymbols(),
                        node.getFilter(),
                        node.getLeftPartitionSymbol(),
                        node.getRightPartitionSymbol(),
                        node.getKdbTree());
            }

            RowExpression leftEffectivePredicate = effectivePredicateExtractor.extract(node.getLeft(), session);
            RowExpression rightEffectivePredicate = effectivePredicateExtractor.extract(node.getRight(), session);
            RowExpression joinPredicate = node.getFilter();

            RowExpression leftPredicate;
            RowExpression rightPredicate;
            RowExpression postJoinPredicate;
            RowExpression newJoinPredicate;

            switch (node.getType()) {
                case INNER:
                    InnerJoinPushDownResult innerJoinPushDownResult = processInnerJoin(
                            inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            nodeLeftOutput);
                    leftPredicate = innerJoinPushDownResult.getLeftPredicate();
                    rightPredicate = innerJoinPushDownResult.getRightPredicate();
                    postJoinPredicate = innerJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = innerJoinPushDownResult.getJoinPredicate();
                    break;
                case LEFT:
                    OuterJoinPushDownResult leftOuterJoinPushDownResult = processLimitedOuterJoin(
                            inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            nodeLeftOutput);
                    leftPredicate = leftOuterJoinPushDownResult.getOuterJoinPredicate();
                    rightPredicate = leftOuterJoinPushDownResult.getInnerJoinPredicate();
                    postJoinPredicate = leftOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = leftOuterJoinPushDownResult.getJoinPredicate();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported spatial join type: " + node.getType());
            }

            newJoinPredicate = simplifyExpression(newJoinPredicate);
            verify(!newJoinPredicate.equals(FALSE_CONSTANT), "Spatial join predicate is missing");

            PlanNode leftSource = context.rewrite(node.getLeft(), leftPredicate);
            PlanNode rightSource = context.rewrite(node.getRight(), rightPredicate);

            PlanNode output = node;
            if (leftSource != node.getLeft() ||
                    rightSource != node.getRight() ||
                    !areExpressionsEquivalent(newJoinPredicate, joinPredicate)) {
                // Create identity projections for all existing symbols
                Assignments.Builder leftProjections = Assignments.builder()
                        .putAll(identityAssignments(planSymbolAllocator.getTypes(), node.getLeft().getOutputSymbols()));

                Assignments.Builder rightProjections = Assignments.builder()
                        .putAll(identityAssignments(planSymbolAllocator.getTypes(), node.getRight().getOutputSymbols()));

                leftSource = new ProjectNode(idAllocator.getNextId(), leftSource, leftProjections.build());
                rightSource = new ProjectNode(idAllocator.getNextId(), rightSource, rightProjections.build());

                output = new SpatialJoinNode(
                        node.getId(),
                        node.getType(),
                        leftSource,
                        rightSource,
                        node.getOutputSymbols(),
                        newJoinPredicate,
                        node.getLeftPartitionSymbol(),
                        node.getRightPartitionSymbol(),
                        node.getKdbTree());
            }

            if (!postJoinPredicate.equals(TRUE_CONSTANT)) {
                output = new FilterNode(idAllocator.getNextId(), output, postJoinPredicate);
            }

            return output;
        }

        private VariableReferenceExpression variableForExpression(RowExpression expression)
        {
            if (expression instanceof VariableReferenceExpression) {
                return (VariableReferenceExpression) expression;
            }

            Symbol symbol = planSymbolAllocator.newSymbol(expression);

            return toVariableReference(symbol, planSymbolAllocator.getTypes());
        }

        private OuterJoinPushDownResult processLimitedOuterJoin(RowExpression inheritedPredicate, RowExpression outerEffectivePredicate, RowExpression innerEffectivePredicate, RowExpression joinPredicate, Collection<VariableReferenceExpression> outerVariables)
        {
            checkArgument(Iterables.all(VariablesExtractor.extractUnique(outerEffectivePredicate), in(outerVariables)), "outerEffectivePredicate must only contain variables from outerVariables");
            checkArgument(Iterables.all(VariablesExtractor.extractUnique(innerEffectivePredicate), not(in(outerVariables))), "innerEffectivePredicate must not contain variables from outerVariables");

            ImmutableList.Builder<RowExpression> outerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> innerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> postJoinConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> joinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            postJoinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic)));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            outerEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(outerEffectivePredicate);
            innerEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(innerEffectivePredicate);
            joinConjuncts.addAll(filter(extractConjuncts(joinPredicate), not(determinismEvaluator::isDeterministic)));
            joinPredicate = logicalRowExpressions.filterDeterministicConjuncts(joinPredicate);

            // Generate equality inferences
            RowExpressionEqualityInference inheritedInference = createEqualityInference(inheritedPredicate);
            RowExpressionEqualityInference outerInference = createEqualityInference(inheritedPredicate, outerEffectivePredicate);

            RowExpressionEqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(in(outerVariables));
            RowExpression outerOnlyInheritedEqualities = RowExpressionUtils.combineConjuncts(equalityPartition.getScopeEqualities());
            RowExpressionEqualityInference potentialNullSymbolInference = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, innerEffectivePredicate, joinPredicate);

            // See if we can push inherited predicates down
            for (RowExpression conjunct : nonInferrableConjuncts(inheritedPredicate)) {
                RowExpression outerRewritten = outerInference.rewriteExpression(conjunct, in(outerVariables));
                if (outerRewritten != null) {
                    outerPushdownConjuncts.add(outerRewritten);

                    // A conjunct can only be pushed down into an inner side if it can be rewritten in terms of the outer side
                    RowExpression innerRewritten = potentialNullSymbolInference.rewriteExpression(outerRewritten, not(in(outerVariables)));
                    if (innerRewritten != null) {
                        innerPushdownConjuncts.add(innerRewritten);
                    }
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }
            // Add the equalities from the inferences back in
            outerPushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            // See if we can push down any outer effective predicates to the inner side
            for (RowExpression conjunct : nonInferrableConjuncts(outerEffectivePredicate)) {
                RowExpression rewritten = potentialNullSymbolInference.rewriteExpression(conjunct, not(in(outerVariables)));
                if (rewritten != null) {
                    innerPushdownConjuncts.add(rewritten);
                }
            }

            // See if we can push down join predicates to the inner side
            for (RowExpression conjunct : nonInferrableConjuncts(joinPredicate)) {
                RowExpression innerRewritten = potentialNullSymbolInference.rewriteExpression(conjunct, not(in(outerVariables)));
                if (innerRewritten != null) {
                    innerPushdownConjuncts.add(innerRewritten);
                }
                else {
                    joinConjuncts.add(conjunct);
                }
            }

            // Push outer and join equalities into the inner side. For example:
            // SELECT * FROM nation LEFT OUTER JOIN region ON nation.regionkey = region.regionkey and nation.name = region.name WHERE nation.name = 'blah'

            RowExpressionEqualityInference potentialNullSymbolInferenceWithoutInnerInferred = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, joinPredicate);
            innerPushdownConjuncts.addAll(potentialNullSymbolInferenceWithoutInnerInferred.generateEqualitiesPartitionedBy(not(in(outerVariables))).getScopeEqualities());

            // TODO: we can further improve simplifying the equalities by considering other relationships from the outer side
            RowExpressionEqualityInference.EqualityPartition joinEqualityPartition = createEqualityInference(joinPredicate).generateEqualitiesPartitionedBy(not(in(outerVariables)));
            innerPushdownConjuncts.addAll(joinEqualityPartition.getScopeEqualities());
            joinConjuncts.addAll(joinEqualityPartition.getScopeComplementEqualities())
                    .addAll(joinEqualityPartition.getScopeStraddlingEqualities());

            return new OuterJoinPushDownResult(RowExpressionUtils.combineConjuncts(outerPushdownConjuncts.build()),
                    RowExpressionUtils.combineConjuncts(innerPushdownConjuncts.build()),
                    RowExpressionUtils.combineConjuncts(joinConjuncts.build()),
                    RowExpressionUtils.combineConjuncts(postJoinConjuncts.build()));
        }

        private static class OuterJoinPushDownResult
        {
            private final RowExpression outerJoinPredicate;
            private final RowExpression innerJoinPredicate;
            private final RowExpression joinPredicate;
            private final RowExpression postJoinPredicate;

            private OuterJoinPushDownResult(RowExpression outerJoinPredicate, RowExpression innerJoinPredicate, RowExpression joinPredicate, RowExpression postJoinPredicate)
            {
                this.outerJoinPredicate = outerJoinPredicate;
                this.innerJoinPredicate = innerJoinPredicate;
                this.joinPredicate = joinPredicate;
                this.postJoinPredicate = postJoinPredicate;
            }

            private RowExpression getOuterJoinPredicate()
            {
                return outerJoinPredicate;
            }

            private RowExpression getInnerJoinPredicate()
            {
                return innerJoinPredicate;
            }

            public RowExpression getJoinPredicate()
            {
                return joinPredicate;
            }

            private RowExpression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private InnerJoinPushDownResult processInnerJoin(RowExpression inheritedPredicate, RowExpression leftEffectivePredicate, RowExpression rightEffectivePredicate, RowExpression joinPredicate, Collection<VariableReferenceExpression> leftVariables)
        {
            checkArgument(Iterables.all(VariablesExtractor.extractUnique(leftEffectivePredicate), in(leftVariables)), "leftEffectivePredicate must only contain variables from leftVariables");
            checkArgument(Iterables.all(VariablesExtractor.extractUnique(rightEffectivePredicate), not(in(leftVariables))), "rightEffectivePredicate must not contain variables from leftVariables");

            ImmutableList.Builder<RowExpression> leftPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> rightPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> joinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            joinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic)));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            joinConjuncts.addAll(filter(extractConjuncts(joinPredicate), not(determinismEvaluator::isDeterministic)));
            joinPredicate = logicalRowExpressions.filterDeterministicConjuncts(joinPredicate);

            leftEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(leftEffectivePredicate);
            rightEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(rightEffectivePredicate);

            // Generate equality inferences
            RowExpressionEqualityInference allInference = new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .addEqualityInference(inheritedPredicate, leftEffectivePredicate, rightEffectivePredicate, joinPredicate)
                    .build();
            RowExpressionEqualityInference allInferenceWithoutLeftInferred = new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .addEqualityInference(inheritedPredicate, rightEffectivePredicate, joinPredicate)
                    .build();
            RowExpressionEqualityInference allInferenceWithoutRightInferred = new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .addEqualityInference(inheritedPredicate, leftEffectivePredicate, joinPredicate)
                    .build();

            // Sort through conjuncts in inheritedPredicate that were not used for inference
            for (RowExpression conjunct : new RowExpressionEqualityInference.Builder(metadata, typeManager).nonInferrableConjuncts(inheritedPredicate)) {
                RowExpression leftRewrittenConjunct = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (leftRewrittenConjunct != null) {
                    leftPushDownConjuncts.add(leftRewrittenConjunct);
                }

                RowExpression rightRewrittenConjunct = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rightRewrittenConjunct != null) {
                    rightPushDownConjuncts.add(rightRewrittenConjunct);
                }

                // Drop predicate after join only if unable to push down to either side
                if (leftRewrittenConjunct == null && rightRewrittenConjunct == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            // See if we can push the right effective predicate to the left side
            for (RowExpression conjunct : new RowExpressionEqualityInference.Builder(metadata, typeManager).nonInferrableConjuncts(rightEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (rewritten != null) {
                    leftPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push the left effective predicate to the right side
            for (RowExpression conjunct : new RowExpressionEqualityInference.Builder(metadata, typeManager).nonInferrableConjuncts(leftEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rewritten != null) {
                    rightPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push any parts of the join predicates to either side
            for (RowExpression conjunct : new RowExpressionEqualityInference.Builder(metadata, typeManager).nonInferrableConjuncts(joinPredicate)) {
                RowExpression leftRewritten = allInference.rewriteExpression(conjunct, in(leftVariables));
                if (leftRewritten != null) {
                    leftPushDownConjuncts.add(leftRewritten);
                }

                RowExpression rightRewritten = allInference.rewriteExpression(conjunct, not(in(leftVariables)));
                if (rightRewritten != null) {
                    rightPushDownConjuncts.add(rightRewritten);
                }

                if (leftRewritten == null && rightRewritten == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            // Add equalities from the inference back in
            leftPushDownConjuncts.addAll(allInferenceWithoutLeftInferred.generateEqualitiesPartitionedBy(in(leftVariables)).getScopeEqualities());
            rightPushDownConjuncts.addAll(allInferenceWithoutRightInferred.generateEqualitiesPartitionedBy(not(in(leftVariables))).getScopeEqualities());
            joinConjuncts.addAll(allInference.generateEqualitiesPartitionedBy(in(leftVariables)::apply).getScopeStraddlingEqualities()); // scope straddling equalities get dropped in as part of the join predicate

            return new Rewriter.InnerJoinPushDownResult(
                    RowExpressionUtils.combineConjuncts(leftPushDownConjuncts.build()),
                    RowExpressionUtils.combineConjuncts(rightPushDownConjuncts.build()),
                    RowExpressionUtils.combineConjuncts(joinConjuncts.build()), TRUE_CONSTANT);
        }

        private static class InnerJoinPushDownResult
        {
            private final RowExpression leftPredicate;
            private final RowExpression rightPredicate;
            private final RowExpression joinPredicate;
            private final RowExpression postJoinPredicate;

            private InnerJoinPushDownResult(RowExpression leftPredicate, RowExpression rightPredicate, RowExpression joinPredicate, RowExpression postJoinPredicate)
            {
                this.leftPredicate = leftPredicate;
                this.rightPredicate = rightPredicate;
                this.joinPredicate = joinPredicate;
                this.postJoinPredicate = postJoinPredicate;
            }

            private RowExpression getLeftPredicate()
            {
                return leftPredicate;
            }

            private RowExpression getRightPredicate()
            {
                return rightPredicate;
            }

            private RowExpression getJoinPredicate()
            {
                return joinPredicate;
            }

            private RowExpression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private RowExpression extractJoinPredicate(JoinNode joinNode)
        {
            ImmutableList.Builder<RowExpression> builder = ImmutableList.builder();
            for (JoinNode.EquiJoinClause equiJoinClause : joinNode.getCriteria()) {
                builder.add(toRowExpression(equiJoinClause));
            }
            joinNode.getFilter().ifPresent(builder::add);
            return RowExpressionUtils.combineConjuncts(builder.build());
        }

        private RowExpression toRowExpression(JoinNode.EquiJoinClause equiJoinClause)
        {
            return buildEqualsExpression(toVariableReference(equiJoinClause.getLeft(), planSymbolAllocator.getTypes()),
                    toVariableReference(equiJoinClause.getRight(), planSymbolAllocator.getTypes()));
        }

        private JoinNode tryNormalizeToOuterToInnerJoin(JoinNode node, RowExpression inheritedPredicate)
        {
            checkArgument(EnumSet.of(INNER, RIGHT, LEFT, FULL).contains(node.getType()), "Unsupported join type: %s", node.getType());

            if (node.getType() == INNER) {
                return node;
            }

            List<VariableReferenceExpression> nodeLeftOutput = toVariableReferences(node.getLeft().getOutputSymbols(), planSymbolAllocator.getTypes());
            List<VariableReferenceExpression> nodeRightOutput = toVariableReferences(node.getRight().getOutputSymbols(), planSymbolAllocator.getTypes());

            if (node.getType() == JoinNode.Type.FULL) {
                boolean canConvertToLeftJoin = canConvertOuterToInner(nodeLeftOutput, inheritedPredicate);
                boolean canConvertToRightJoin = canConvertOuterToInner(nodeRightOutput, inheritedPredicate);
                if (!canConvertToLeftJoin && !canConvertToRightJoin) {
                    return node;
                }
                if (canConvertToLeftJoin && canConvertToRightJoin) {
                    return new JoinNode(node.getId(), INNER,
                            node.getLeft(), node.getRight(), node.getCriteria(), node.getOutputSymbols(), node.getFilter(),
                            node.getLeftHashSymbol(), node.getRightHashSymbol(), node.getDistributionType(), node.isSpillable(), node.getDynamicFilters());
                }
                else {
                    return new JoinNode(node.getId(), canConvertToLeftJoin ? LEFT : RIGHT,
                            node.getLeft(), node.getRight(), node.getCriteria(), node.getOutputSymbols(), node.getFilter(),
                            node.getLeftHashSymbol(), node.getRightHashSymbol(), node.getDistributionType(), node.isSpillable(), node.getDynamicFilters());
                }
            }

            if (node.getType() == LEFT && !canConvertOuterToInner(nodeRightOutput, inheritedPredicate) ||
                    node.getType() == RIGHT && !canConvertOuterToInner(nodeLeftOutput, inheritedPredicate)) {
                return node;
            }
            return new JoinNode(node.getId(), INNER,
                    node.getLeft(), node.getRight(), node.getCriteria(), node.getOutputSymbols(), node.getFilter(),
                    node.getLeftHashSymbol(), node.getRightHashSymbol(), node.getDistributionType(), node.isSpillable(), node.getDynamicFilters());
        }

        private boolean canConvertOuterToInner(List<VariableReferenceExpression> innerVariablesForOuterJoin, RowExpression inheritedPredicate)
        {
            Set<VariableReferenceExpression> innerVariables = ImmutableSet.copyOf(innerVariablesForOuterJoin);
            for (RowExpression conjunct : extractConjuncts(inheritedPredicate)) {
                if (determinismEvaluator.isDeterministic(conjunct)) {
                    // Ignore a conjunct for this test if we can not deterministically get responses from it
                    RowExpression response = nullInputEvaluator(innerVariables, conjunct);
                    if (response == null || Expressions.isNull(response) || FALSE_CONSTANT.equals(response)) {
                        // If there is a single conjunct that returns FALSE or NULL given all NULL inputs for the inner side symbols of an outer join
                        // then this conjunct removes all effects of the outer join, and effectively turns this into an equivalent of an inner join.
                        // So, let's just rewrite this join as an INNER join
                        return true;
                    }
                }
            }
            return false;
        }

        // Temporary implementation for joins because the SimplifyExpressions optimizers can not run properly on join clauses
        private RowExpression simplifyExpression(RowExpression expression)
        {
            return new RowExpressionOptimizer(metadata).optimize(expression, RowExpressionInterpreter.Level.SERIALIZABLE, session.toConnectorSession());
        }

        private boolean areExpressionsEquivalent(RowExpression leftExpression, RowExpression rightExpression)
        {
            return expressionEquivalence.areExpressionsEquivalent(simplifyExpression(leftExpression), simplifyExpression(rightExpression));
        }

        //Evaluates an expression's response to binding the specified input symbols to NULL
        private RowExpression nullInputEvaluator(final Collection<VariableReferenceExpression> nullSymbols, RowExpression expression)
        {
            expression = RowExpressionNodeInliner.replaceExpression(expression, nullSymbols.stream()
                    .collect(Collectors.toMap(identity(), variable -> constantNull(variable.getType()))));
            return new RowExpressionOptimizer(metadata).optimize(expression, RowExpressionInterpreter.Level.OPTIMIZED, session.toConnectorSession());
        }

        private Predicate<RowExpression> joinEqualityExpression(final Collection<VariableReferenceExpression> leftVariables)
        {
            return expression -> {
                // At this point in time, our join predicates need to be deterministic
                if (determinismEvaluator.isDeterministic(expression) && isOperation(expression, EQUAL)) {
                    Set<VariableReferenceExpression> variables1 = VariablesExtractor.extractUnique(getLeft(expression));
                    Set<VariableReferenceExpression> variables2 = VariablesExtractor.extractUnique(getRight(expression));
                    if (variables1.isEmpty() || variables2.isEmpty()) {
                        return false;
                    }
                    return (Iterables.all(variables1, in(leftVariables)) && Iterables.all(variables2, not(in(leftVariables)))) ||
                            (Iterables.all(variables2, in(leftVariables)) && Iterables.all(variables1, not(in(leftVariables))));
                }
                return false;
            };
        }

        private boolean isOperation(RowExpression expression, OperatorType type)
        {
            if (expression instanceof CallExpression) {
                Optional<OperatorType> operatorType = Signature.getOperatorType(((CallExpression) expression).getSignature().getName());
                if (operatorType.isPresent()) {
                    return operatorType.get().equals(type);
                }
            }
            return false;
        }

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();
            if (!extractConjuncts(inheritedPredicate).contains(toVariableReference(node.getSemiJoinOutput(), planSymbolAllocator.getTypes()))) {
                return visitNonFilteringSemiJoin(node, context);
            }
            return visitFilteringSemiJoin(node, context);
        }

        private PlanNode visitNonFilteringSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();
            List<RowExpression> sourceConjuncts = new ArrayList<>();
            List<RowExpression> postJoinConjuncts = new ArrayList<>();

            // TODO: see if there are predicates that can be inferred from the semi join output

            PlanNode rewrittenFilteringSource = context.defaultRewrite(node.getFilteringSource(), TRUE_CONSTANT);

            // Push inheritedPredicates down to the source if they don't involve the semi join output
            RowExpressionEqualityInference inheritedInference = new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .addEqualityInference(inheritedPredicate)
                    .build();
            for (RowExpression conjunct : new RowExpressionEqualityInference.Builder(metadata, typeManager).nonInferrableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = inheritedInference.rewriteExpressionAllowNonDeterministic(conjunct, in(toVariableReferences(node.getSource().getOutputSymbols(), planSymbolAllocator.getTypes())));
                // Since each source row is reflected exactly once in the output, ok to push non-deterministic predicates down
                if (rewrittenConjunct != null) {
                    sourceConjuncts.add(rewrittenConjunct);
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // Add the inherited equality predicates back in
            RowExpressionEqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(
                    in(toVariableReferences(node.getSource().getOutputSymbols(), planSymbolAllocator.getTypes()))::apply);
            sourceConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), RowExpressionUtils.combineConjuncts(sourceConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource() || rewrittenFilteringSource != node.getFilteringSource()) {
                output = new SemiJoinNode(node.getId(),
                        rewrittenSource,
                        rewrittenFilteringSource,
                        node.getSourceJoinSymbol(),
                        node.getFilteringSourceJoinSymbol(),
                        node.getSemiJoinOutput(),
                        node.getSourceHashSymbol(),
                        node.getFilteringSourceHashSymbol(),
                        node.getDistributionType(),
                        Optional.empty());
            }
            if (!postJoinConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, RowExpressionUtils.combineConjuncts(postJoinConjuncts));
            }
            return output;
        }

        private PlanNode visitFilteringSemiJoin(SemiJoinNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();
            RowExpression deterministicInheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);
            RowExpression sourceEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(effectivePredicateExtractor.extract(node.getSource(), session));
            RowExpression filteringSourceEffectivePredicate = logicalRowExpressions.filterDeterministicConjuncts(effectivePredicateExtractor.extract(node.getFilteringSource(), session));
            RowExpression joinExpression = buildEqualsExpression(
                    toVariableReference(node.getSourceJoinSymbol(), planSymbolAllocator.getTypes()),
                    toVariableReference(node.getFilteringSourceJoinSymbol(), planSymbolAllocator.getTypes()));

            List<VariableReferenceExpression> sourceVariables = toVariableReferences(node.getSource().getOutputSymbols(), planSymbolAllocator.getTypes());
            List<VariableReferenceExpression> filteringSourceVariables = toVariableReferences(node.getFilteringSource().getOutputSymbols(), planSymbolAllocator.getTypes());

            List<RowExpression> sourceConjuncts = new ArrayList<>();
            List<RowExpression> filteringSourceConjuncts = new ArrayList<>();
            List<RowExpression> postJoinConjuncts = new ArrayList<>();

            // Generate equality inferences
            RowExpressionEqualityInference allInference = createEqualityInference(deterministicInheritedPredicate, sourceEffectivePredicate, filteringSourceEffectivePredicate, joinExpression);
            RowExpressionEqualityInference allInferenceWithoutSourceInferred = createEqualityInference(deterministicInheritedPredicate, filteringSourceEffectivePredicate, joinExpression);
            RowExpressionEqualityInference allInferenceWithoutFilteringSourceInferred = createEqualityInference(deterministicInheritedPredicate, sourceEffectivePredicate, joinExpression);

            // Push inheritedPredicates down to the source if they don't involve the semi join output
            for (RowExpression conjunct : nonInferrableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = allInference.rewriteExpressionAllowNonDeterministic(conjunct, in(sourceVariables));
                // Since each source row is reflected exactly once in the output, ok to push non-deterministic predicates down
                if (rewrittenConjunct != null) {
                    sourceConjuncts.add(rewrittenConjunct);
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // Push inheritedPredicates down to the filtering source if possible
            for (RowExpression conjunct : nonInferrableConjuncts(deterministicInheritedPredicate)) {
                RowExpression rewrittenConjunct = allInference.rewriteExpression(conjunct, in(filteringSourceVariables));
                // We cannot push non-deterministic predicates to filtering side. Each filtering side row have to be
                // logically reevaluated for each source row.
                if (rewrittenConjunct != null) {
                    filteringSourceConjuncts.add(rewrittenConjunct);
                }
            }

            // move effective predicate conjuncts source <-> filter
            // See if we can push the filtering source effective predicate to the source side
            for (RowExpression conjunct : nonInferrableConjuncts(filteringSourceEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(sourceVariables));
                if (rewritten != null) {
                    sourceConjuncts.add(rewritten);
                }
            }

            // See if we can push the source effective predicate to the filtering soruce side
            for (RowExpression conjunct : nonInferrableConjuncts(sourceEffectivePredicate)) {
                RowExpression rewritten = allInference.rewriteExpression(conjunct, in(filteringSourceVariables));
                if (rewritten != null) {
                    filteringSourceConjuncts.add(rewritten);
                }
            }

            // Add equalities from the inference back in
            sourceConjuncts.addAll(allInferenceWithoutSourceInferred.generateEqualitiesPartitionedBy(in(sourceVariables)).getScopeEqualities());
            filteringSourceConjuncts.addAll(allInferenceWithoutFilteringSourceInferred.generateEqualitiesPartitionedBy(in(filteringSourceVariables)).getScopeEqualities());

            // Add dynamic filtering predicate
            Optional<String> dynamicFilterId = node.getDynamicFilterId();
            if (!dynamicFilterId.isPresent() && isEnableDynamicFiltering(session) && dynamicFiltering) {
                dynamicFilterId = Optional.of(idAllocator.getNextId().toString());
                Symbol sourceSymbol = node.getSourceJoinSymbol();
                sourceConjuncts.add(createDynamicFilterRowExpression(metadata, typeManager, dynamicFilterId.get(), planSymbolAllocator.getTypes().get(sourceSymbol), SymbolUtils.toSymbolReference(sourceSymbol), Optional.empty()));
            }

            PlanNode rewrittenSource = context.rewrite(node.getSource(), RowExpressionUtils.combineConjuncts(sourceConjuncts));
            PlanNode rewrittenFilteringSource = context.rewrite(node.getFilteringSource(), RowExpressionUtils.combineConjuncts(filteringSourceConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource() || rewrittenFilteringSource != node.getFilteringSource() || !dynamicFilterId.equals(node.getDynamicFilterId())) {
                output = new SemiJoinNode(
                        node.getId(),
                        rewrittenSource,
                        rewrittenFilteringSource,
                        node.getSourceJoinSymbol(),
                        node.getFilteringSourceJoinSymbol(),
                        node.getSemiJoinOutput(),
                        node.getSourceHashSymbol(),
                        node.getFilteringSourceHashSymbol(),
                        node.getDistributionType(),
                        dynamicFilterId);
            }
            if (!postJoinConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, RowExpressionUtils.combineConjuncts(postJoinConjuncts));
            }
            return output;
        }

        private Iterable<RowExpression> nonInferrableConjuncts(RowExpression inheritedPredicate)
        {
            return new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .nonInferrableConjuncts(inheritedPredicate);
        }

        private RowExpressionEqualityInference createEqualityInference(RowExpression... expressions)
        {
            return new RowExpressionEqualityInference.Builder(metadata, typeManager)
                    .addEqualityInference(expressions)
                    .build();
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<RowExpression> context)
        {
            if (node.hasEmptyGroupingSet()) {
                // TODO: in case of grouping sets, we should be able to push the filters over grouping keys below the aggregation
                // and also preserve the filter above the aggregation if it has an empty grouping set
                return visitPlan(node, context);
            }

            RowExpression inheritedPredicate = context.get();

            RowExpressionEqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<RowExpression> pushdownConjuncts = new ArrayList<>();
            List<RowExpression> postAggregationConjuncts = new ArrayList<>();

            List<VariableReferenceExpression> groupingKeyVariables = toVariableReferences(node.getGroupingKeys(), planSymbolAllocator.getTypes());

            // Strip out non-deterministic conjuncts
            postAggregationConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic))));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (RowExpression conjunct : nonInferrableConjuncts(inheritedPredicate)) {
                if (node.getGroupIdSymbol().isPresent() && VariablesExtractor.extractUnique(conjunct).contains(toVariableReference(node.getGroupIdSymbol().get(), planSymbolAllocator.getTypes()))) {
                    // aggregation operator synthesizes outputs for group ids corresponding to the global grouping set (i.e., ()), so we
                    // need to preserve any predicates that evaluate the group id to run after the aggregation
                    // TODO: we should be able to infer if conditions on grouping() correspond to global grouping sets to determine whether
                    // we need to do this for each specific case
                    postAggregationConjuncts.add(conjunct);
                    continue;
                }

                RowExpression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(groupingKeyVariables));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postAggregationConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            RowExpressionEqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(groupingKeyVariables)::apply);
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), RowExpressionUtils.combineConjuncts(pushdownConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource()) {
                output = new AggregationNode(node.getId(),
                        rewrittenSource,
                        node.getAggregations(),
                        node.getGroupingSets(),
                        ImmutableList.of(),
                        node.getStep(),
                        node.getHashSymbol(),
                        node.getGroupIdSymbol());
            }
            if (!postAggregationConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, RowExpressionUtils.combineConjuncts(postAggregationConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<RowExpression> context)
        {
            RowExpression inheritedPredicate = context.get();

            RowExpressionEqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<RowExpression> pushdownConjuncts = new ArrayList<>();
            List<RowExpression> postUnnestConjuncts = new ArrayList<>();

            // Strip out non-deterministic conjuncts
            postUnnestConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(determinismEvaluator::isDeterministic))));
            inheritedPredicate = logicalRowExpressions.filterDeterministicConjuncts(inheritedPredicate);

            List<VariableReferenceExpression> nodeReplicate = toVariableReferences(node.getReplicateSymbols(), planSymbolAllocator.getTypes());

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (RowExpression conjunct : nonInferrableConjuncts(inheritedPredicate)) {
                RowExpression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(nodeReplicate));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postUnnestConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            RowExpressionEqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(nodeReplicate)::apply);
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), RowExpressionUtils.combineConjuncts(pushdownConjuncts));

            PlanNode output = node;
            if (rewrittenSource != node.getSource()) {
                output = new UnnestNode(node.getId(), rewrittenSource, node.getReplicateSymbols(), node.getUnnestSymbols(), node.getOrdinalitySymbol());
            }
            if (!postUnnestConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, RowExpressionUtils.combineConjuncts(postUnnestConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitSample(SampleNode node, RewriteContext<RowExpression> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<RowExpression> context)
        {
            RowExpression predicate = simplifyExpression(context.get());

            if (!TRUE_CONSTANT.equals(predicate)) {
                return new FilterNode(idAllocator.getNextId(), node, predicate);
            }

            return node;
        }

        @Override
        public PlanNode visitAssignUniqueId(AssignUniqueId node, RewriteContext<RowExpression> context)
        {
            Set<VariableReferenceExpression> predicateVariables = VariablesExtractor.extractUnique(context.get());
            checkState(!predicateVariables.contains(toVariableReference(node.getIdColumn(), planSymbolAllocator.getTypes())), "UniqueId in predicate is not yet supported");
            return context.defaultRewrite(node, context.get());
        }

        private static CallExpression buildEqualsExpression(RowExpression left, RowExpression right)
        {
            Signature signature = Signature.internalOperator(EQUAL, BOOLEAN, ImmutableList.of(left.getType(), right.getType()));
            return call(signature, BOOLEAN, left, right);
        }
    }

    private static boolean isDynamicFilterComparisonOperator(String name)
    {
        OperatorType operatorType = unmangleOperator(name);
        return operatorType.equals(LESS_THAN) ||
                operatorType.equals(LESS_THAN_OR_EQUAL) ||
                operatorType.equals(GREATER_THAN) ||
                operatorType.equals(GREATER_THAN_OR_EQUAL);
    }
}