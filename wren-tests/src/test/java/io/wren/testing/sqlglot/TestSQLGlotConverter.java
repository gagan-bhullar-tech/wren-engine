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

package io.wren.testing.sqlglot;

import io.wren.base.SessionContext;
import io.wren.base.config.SQLGlotConfig;
import io.wren.main.sqlglot.SQLGlot;
import io.wren.main.sqlglot.SQLGlotConverter;
import io.wren.testing.AbstractSqlConverterTest;
import io.wren.testing.TestingSQLGlotServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.wren.base.config.SQLGlotConfig.createConfigWithFreePort;
import static io.wren.main.sqlglot.SQLGlot.Dialect.BIGQUERY;
import static io.wren.main.sqlglot.SQLGlot.Dialect.DUCKDB;
import static io.wren.main.sqlglot.SQLGlot.Dialect.SNOWFLAKE;
import static org.assertj.core.api.Assertions.assertThat;

@Test
public class TestSQLGlotConverter
        extends AbstractSqlConverterTest
{
    private static final SessionContext DEFAULT_SESSION_CONTEXT = SessionContext.builder().build();

    private TestingSQLGlotServer testingSQLGlotServer;
    private SQLGlot sqlglot;

    @BeforeClass
    public void setup()
    {
        SQLGlotConfig config = createConfigWithFreePort();
        testingSQLGlotServer = new TestingSQLGlotServer(config);
        sqlglot = new SQLGlot(config);
    }

    @AfterClass
    public void close()
    {
        testingSQLGlotServer.close();
        sqlglot.close();
    }

    @Test
    public void testGenerateArray()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setReadDialect(BIGQUERY)
                .setWriteDialect(DUCKDB)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT generate_array(1, 10)",
                "SELECT GENERATE_SERIES(1, 10)");
    }

    @Test
    public void testSubstring()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(BIGQUERY)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT substring('Thomas' from 2 for 3)",
                "SELECT SUBSTRING('Thomas', 2, 3)");
    }

    @Test
    public void testArray()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(DUCKDB)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT ARRAY[1,2,3][1]",
                "SELECT ([1, 2, 3])[1]");
    }

    @Test(enabled = false, description = "SQLGlot does not correctly handle GENERATE_TIMESTAMP_ARRAY for Snowflake")
    public void testGenerateTimestampArray()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setReadDialect(BIGQUERY)
                .setWriteDialect(SNOWFLAKE)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-07 00:00:00', INTERVAL 1 DAY) AS timestamp_array;",
                "UNSUPPORTED");
    }

    @Test(enabled = false, description = "SQLGlot does not correctly handle UNNEST for Snowflake")
    public void testUnnest()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(SNOWFLAKE)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT * FROM UNNEST(ARRAY[1, 2, 3])",
                "SELECT value::INTEGER FROM TABLE(FLATTEN(input => PARSE_JSON('[1, 2, 3]')));");
    }

    @Test
    public void testReplaceColumnAliasInUnnest()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(BIGQUERY)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT a.id FROM UNNEST(ARRAY[1]) as a(id)",
                "SELECT id FROM UNNEST([1]) AS id");

        assertConvert(sqlGlotConverter,
                "SELECT a.id FROM (SELECT a.id FROM UNNEST(ARRAY[1]) as a(id)) a",
                "SELECT a.id FROM (SELECT id FROM UNNEST([1]) AS id) AS a");
    }

    @Test
    public void testRewriteArithemetic()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(BIGQUERY)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT TIMESTAMP '2023-07-04 09:41:43.805201' + INTERVAL '1 YEAR'",
                "SELECT CAST('2023-07-04 09:41:43.805201' AS DATETIME) + INTERVAL '1' YEAR");

        assertConvert(sqlGlotConverter,
                "SELECT DATE '2023-07-04' + INTERVAL '1 YEAR'",
                "SELECT CAST('2023-07-04' AS DATE) + INTERVAL '1' YEAR");
    }

    @Test
    public void testBigQueryGroupByOrdinal()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(BIGQUERY)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT custkey, COUNT(*) AS cnt FROM \"Order\" GROUP BY 1",
                "SELECT custkey, COUNT(*) AS cnt FROM `Order` GROUP BY 1");
    }

    @Test
    public void testDereferenceExpression()
    {
        SQLGlotConverter sqlGlotConverter = SQLGlotConverter.builder()
                .setSQLGlot(sqlglot)
                .setWriteDialect(BIGQUERY)
                .build();

        assertConvert(sqlGlotConverter,
                "SELECT t.\"transform(Customer.orders, (orderItem) -> orderItem.orderstatus)\" from t",
                "SELECT t.`transform(Customer.orders, (orderItem) -> orderItem.orderstatus)` FROM t");
    }

    private static void assertConvert(SQLGlotConverter sqlGlotConverter, String sql, String expected)
    {
        assertThat(sqlGlotConverter.convert(sql, DEFAULT_SESSION_CONTEXT)).isEqualTo(expected);
    }
}
