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

package io.graphmdl.sqlrewrite;

import com.google.common.collect.ImmutableList;
import io.graphmdl.base.GraphMDL;
import io.graphmdl.base.SessionContext;
import io.graphmdl.base.dto.Metric;
import io.graphmdl.base.dto.Model;
import io.graphmdl.sqlrewrite.analyzer.Analysis;
import io.trino.sql.QueryUtil;
import io.trino.sql.tree.AliasedRelation;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.DereferenceExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionRelation;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.JoinCriteria;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.Relation;
import io.trino.sql.tree.Table;
import io.trino.sql.tree.With;
import io.trino.sql.tree.WithQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.trino.sql.QueryUtil.equal;
import static io.trino.sql.QueryUtil.joinOn;
import static io.trino.sql.QueryUtil.table;
import static io.trino.sql.tree.DereferenceExpression.getQualifiedName;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableList;
import static java.util.stream.Collectors.toUnmodifiableMap;

public class GraphMDLSqlRewrite
        implements GraphMDLRule
{
    public static final GraphMDLSqlRewrite GRAPHMDL_SQL_REWRITE = new GraphMDLSqlRewrite();

    private GraphMDLSqlRewrite() {}

    @Override
    public Node apply(Node root, SessionContext sessionContext, Analysis analysis, GraphMDL graphMDL)
    {
        Map<String, Query> modelQueries =
                analysis.getModels().stream()
                        .collect(toUnmodifiableMap(Model::getName, Utils::parseModelSql));

        Map<String, Query> metricQueries =
                analysis.getMetrics().stream()
                        .collect(toUnmodifiableMap(Metric::getName, Utils::parseMetricSql));

        Map<String, Query> metricRollupQueries =
                analysis.getMetricRollups().values().stream()
                        .collect(toUnmodifiableMap(rollup -> rollup.getMetric().getName(), Utils::parseMetricRollupSql));

        if (modelQueries.isEmpty()) {
            return root;
        }

        Node rewriteWith = new WithRewriter(modelQueries, metricQueries, metricRollupQueries, analysis).process(root);
        return new Rewriter(analysis).process(rewriteWith);
    }

    /**
     * In MLRewriter, we will add all participated model sql in WITH-QUERY, and rewrite
     * all tables that are models to TableSubQuery in WITH-QUERYs
     * <p>
     * e.g. Given model "foo" and its reference sql is SELECT * FROM t1
     * <pre>
     *     SELECT * FROM foo
     * </pre>
     * will be rewritten to
     * <pre>
     *     WITH foo AS (SELECT * FROM t1)
     *     SELECT * FROM foo
     * </pre>
     * and
     * <pre>
     *     WITH a AS (SELECT * FROM foo)
     *     SELECT * FROM a JOIN b on a.id=b.id
     * </pre>
     * will be rewritten to
     * <pre>
     *     WITH foo AS (SELECT * FROM t1),
     *          a AS (SELECT * FROM foo)
     *     SELECT * FROM a JOIN b on a.id=b.id
     * </pre>
     */
    private static class WithRewriter
            extends BaseRewriter<Void>
    {
        private final Map<String, Query> modelQueries;
        private final Map<String, Query> metricQueries;
        private final Map<String, Query> metricRollupQueries;
        private final Analysis analysis;

        public WithRewriter(
                Map<String, Query> modelQueries,
                Map<String, Query> metricQueries,
                Map<String, Query> metricRollupQueries,
                Analysis analysis)
        {
            this.modelQueries = requireNonNull(modelQueries, "modelQueries is null");
            this.metricQueries = requireNonNull(metricQueries, "metricQueries is null");
            this.metricRollupQueries = requireNonNull(metricRollupQueries, "metricRollupQueries is null");
            this.analysis = requireNonNull(analysis, "analysis is null");
        }

        @Override
        protected Node visitQuery(Query node, Void context)
        {
            List<WithQuery> modelWithQueries = modelQueries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()) // sort here to avoid test failed due to wrong with-query order
                    .map(e -> new WithQuery(new Identifier(e.getKey()), e.getValue(), Optional.empty()))
                    .collect(toUnmodifiableList());

            Collection<WithQuery> relationshipCTEs = analysis.getRelationshipCTE().values();

            List<WithQuery> metricWithQueries = metricQueries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()) // sort here to avoid test failed due to wrong with-query order
                    .map(e -> new WithQuery(new Identifier(e.getKey()), e.getValue(), Optional.empty()))
                    .collect(toUnmodifiableList());

            List<WithQuery> metricRollupWithQueries = metricRollupQueries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()) // sort here to avoid test failed due to wrong with-query order
                    .map(e -> new WithQuery(new Identifier(e.getKey()), e.getValue(), Optional.empty()))
                    .collect(toUnmodifiableList());

            List<WithQuery> withQueries = ImmutableList.<WithQuery>builder()
                    .addAll(modelWithQueries)
                    .addAll(relationshipCTEs)
                    .addAll(metricWithQueries)
                    .addAll(metricRollupWithQueries)
                    .build();

            return new Query(
                    node.getWith()
                            .map(with -> new With(
                                    with.isRecursive(),
                                    // model queries must come first since with-queries may use models
                                    // and tables in with query should all be in order.
                                    Stream.concat(withQueries.stream(), with.getQueries().stream())
                                            .collect(toUnmodifiableList())))
                            .or(() -> Optional.of(new With(false, withQueries))),
                    node.getQueryBody(),
                    node.getOrderBy(),
                    node.getOffset(),
                    node.getLimit());
        }
    }

    private static class Rewriter
            extends BaseRewriter<Void>
    {
        private final Analysis analysis;

        Rewriter(Analysis analysis)
        {
            this.analysis = analysis;
        }

        @Override
        protected Node visitTable(Table node, Void context)
        {
            Node result = node;
            if (analysis.getModelNodeRefs().contains(NodeRef.of(node))) {
                result = applyModelRule(node);
            }

            Set<String> relationshipCTENames = analysis.getReplaceTableWithCTEs().getOrDefault(NodeRef.of(node), Set.of());
            if (relationshipCTENames.size() > 0) {
                result = applyRelationshipRule((Table) result, relationshipCTENames);
            }

            return result;
        }

        @Override
        protected Node visitAliasedRelation(AliasedRelation node, Void context)
        {
            Relation result;

            // rewrite the fields in QueryBody
            if (node.getLocation().isPresent()) {
                result = new AliasedRelation(
                        node.getLocation().get(),
                        visitAndCast(node.getRelation(), context),
                        node.getAlias(),
                        node.getColumnNames());
            }
            else {
                result = new AliasedRelation(
                        visitAndCast(node.getRelation(), context),
                        node.getAlias(),
                        node.getColumnNames());
            }

            Set<String> relationshipCTENames = analysis.getReplaceTableWithCTEs().getOrDefault(NodeRef.of(node), Set.of());
            if (relationshipCTENames.size() > 0) {
                result = applyRelationshipRule(result, relationshipCTENames);
            }
            return result;
        }

        @Override
        protected Node visitFunctionRelation(FunctionRelation node, Void context)
        {
            if (analysis.getMetricRollups().containsKey(NodeRef.of(node))) {
                return new Table(QualifiedName.of(analysis.getMetricRollups().get(NodeRef.of(node)).getMetric().getName()));
            }
            // this should not happen, every MetricRollup node should be captured and syntax checked in StatementAnalyzer
            throw new IllegalArgumentException("MetricRollup node is not replaced");
        }

        @Override
        protected Node visitDereferenceExpression(DereferenceExpression node, Void context)
        {
            return analysis.getRelationshipFields().getOrDefault(NodeRef.of(node), node);
        }

        @Override
        protected Node visitIdentifier(Identifier node, Void context)
        {
            return analysis.getRelationshipFields().getOrDefault(NodeRef.of(node), node);
        }

        // the model is added in with query, and the catalog and schema should be removed
        private Node applyModelRule(Table table)
        {
            return new Table(QualifiedName.of(table.getName().getSuffix()));
        }

        private Relation applyRelationshipRule(Relation table, Set<String> relationshipCTENames)
        {
            Map<String, RelationshipCteGenerator.RelationshipCTEJoinInfo> relationshipInfoMapping = analysis.getRelationshipInfoMapping();
            Set<String> requiredRsCteName = analysis.getRelationshipFields().values().stream()
                    .map(this::getBaseName)
                    .collect(toSet());

            List<RelationshipCteGenerator.RelationshipCTEJoinInfo> cteTables =
                    relationshipCTENames.stream()
                            .filter(name -> requiredRsCteName.contains(analysis.getRelationshipNameMapping().get(name)))
                            .map(name -> analysis.getRelationshipCTE().get(name))
                            .map(WithQuery::getName)
                            .map(Identifier::getValue)
                            .map(QualifiedName::of)
                            .map(name -> relationshipInfoMapping.get(name.toString()))
                            .collect(toUnmodifiableList());

            return leftJoin(table, cteTables);
        }

        private String getBaseName(Expression expression)
        {
            if (expression instanceof DereferenceExpression) {
                return ((DereferenceExpression) expression).getBase().toString();
            }
            else if (expression instanceof Identifier) {
                return ((Identifier) expression).getValue();
            }
            throw new IllegalArgumentException("Unexpected expression: " + expression.getClass().getName());
        }

        private static Relation leftJoin(Relation left, List<RelationshipCteGenerator.RelationshipCTEJoinInfo> relationshipCTEJoinInfos)
        {
            Identifier aliasedName = null;
            if (left instanceof AliasedRelation) {
                aliasedName = ((AliasedRelation) left).getAlias();
            }

            for (RelationshipCteGenerator.RelationshipCTEJoinInfo info : relationshipCTEJoinInfos) {
                left = QueryUtil.leftJoin(left, table(QualifiedName.of(info.getCteName())), replaceIfAliased(info.getCondition(), info.getBaseModelName(), aliasedName));
            }
            return left;
        }

        private static JoinCriteria replaceIfAliased(JoinCriteria original, String baseModelName, Identifier aliasedName)
        {
            if (aliasedName == null) {
                return original;
            }

            ComparisonExpression comparisonExpression = (ComparisonExpression) original.getNodes().get(0);
            DereferenceExpression left = (DereferenceExpression) comparisonExpression.getLeft();
            Optional<QualifiedName> originalTableName = requireNonNull(getQualifiedName(left)).getPrefix();

            if (originalTableName.isPresent() && originalTableName.get().getSuffix().equals(baseModelName)) {
                left = new DereferenceExpression(aliasedName, left.getField());
            }
            return joinOn(equal(left, comparisonExpression.getRight()));
        }
    }
}