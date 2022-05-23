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

package io.cml.pgcatalog.regtype;

import com.google.common.collect.Streams;
import io.cml.spi.connector.Connector;

import javax.inject.Inject;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.cml.sql.PgOidTypeTableInfo.REGCLASS;
import static io.cml.sql.PgOidTypeTableInfo.REGPROC;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class BigQueryPgMetadata
        extends PgMetadata
{
    Connector connector;

    @Inject
    public BigQueryPgMetadata(Connector connector)
    {
        this.connector = requireNonNull(connector, "connector is null");
    }

    @Override
    protected List<RegObject> listRegProc()
    {
        return listRegObject(REGPROC.getNameField(), REGPROC.getTableName());
    }

    @Override
    protected List<RegObject> listRegClass()
    {
        return listRegObject(REGCLASS.getNameField(), REGCLASS.getTableName());
    }

    private List<RegObject> listRegObject(String nameField, String tableName)
    {
        return Streams.stream(connector.directQuery(format("SELECT oid, %s FROM pg_catalog.%s", nameField, tableName)))
                .map(row -> new RegObject((int) row[0], (String) row[1]))
                .collect(toImmutableList());
    }
}
