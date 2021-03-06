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
package com.facebook.presto.sql.analyzer;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.rewrite.StatementRewrite;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.sql.analyzer.SemanticErrorCode.CANNOT_HAVE_AGGREGATIONS_OR_WINDOWS;
import static java.util.Objects.requireNonNull;

public class Analyzer
{
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final AccessControl accessControl;
    private final Session session;
    private final Optional<QueryExplainer> queryExplainer;
    private final List<Expression> parameters;

    public Analyzer(Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            Optional<QueryExplainer> queryExplainer,
            List<Expression> parameters)
    {
        this.session = requireNonNull(session, "session is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.queryExplainer = requireNonNull(queryExplainer, "query explainer is null");
        this.parameters = parameters;
    }

    public Analysis analyze(Statement statement)
    {
        return analyze(statement, false);
    }

    public Analysis analyze(Statement statement, boolean isDescribe)
    {
        //重写语法树 有以下四种重写器，每一个都服务一下。
       /* new DescribeInputRewrite(),
        new DescribeOutputRewrite(),
        new ShowQueriesRewrite(),
        new ExplainRewrite());*/
        Statement rewrittenStatement = StatementRewrite.rewrite(session, metadata, sqlParser, queryExplainer, statement, parameters, accessControl);
        Analysis analysis = new Analysis(rewrittenStatement, parameters, isDescribe);
        StatementAnalyzer analyzer = new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session);
        // 遍历整棵语法树 上面的analysis会在遍历树的时候，被添加形如这样的：analysis.setUpdateType("CREATE TABLE");
        //
        analyzer.analyze(rewrittenStatement, Optional.empty());
        return analysis;
    }

    static void verifyNoAggregatesOrWindowFunctions(FunctionRegistry functionRegistry, Expression predicate, String clause)
    {
        AggregateExtractor extractor = new AggregateExtractor(functionRegistry);
        extractor.process(predicate, null);

        WindowFunctionExtractor windowExtractor = new WindowFunctionExtractor();
        windowExtractor.process(predicate, null);

        List<FunctionCall> found = ImmutableList.copyOf(Iterables.concat(extractor.getAggregates(), windowExtractor.getWindowFunctions()));

        if (!found.isEmpty()) {
            throw new SemanticException(CANNOT_HAVE_AGGREGATIONS_OR_WINDOWS, predicate, "%s cannot contain aggregations or window functions: %s", clause, found);
        }
    }
}
