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
package org.apache.ambari.server.orm;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.configuration.Configuration.DatabaseType;
import org.apache.ambari.server.orm.helpers.ScriptRunner;
import org.apache.ambari.server.orm.helpers.dbms.DbmsHelper;
import org.apache.ambari.server.orm.helpers.dbms.DerbyHelper;
import org.apache.ambari.server.orm.helpers.dbms.GenericDbmsHelper;
import org.apache.ambari.server.orm.helpers.dbms.MySqlHelper;
import org.apache.ambari.server.orm.helpers.dbms.OracleHelper;
import org.apache.ambari.server.orm.helpers.dbms.PostgresHelper;
import org.apache.ambari.server.utils.CustomStringUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.persistence.internal.helper.DBPlatformHelper;
import org.eclipse.persistence.internal.sessions.DatabaseSessionImpl;
import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.SessionLogEntry;
import org.eclipse.persistence.platform.database.DatabasePlatform;
import org.eclipse.persistence.platform.database.DerbyPlatform;
import org.eclipse.persistence.platform.database.MySQLPlatform;
import org.eclipse.persistence.platform.database.OraclePlatform;
import org.eclipse.persistence.platform.database.PostgreSQLPlatform;
import org.eclipse.persistence.sessions.DatabaseLogin;
import org.eclipse.persistence.sessions.DatabaseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.support.JdbcUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DBAccessorImpl implements DBAccessor {
  private static final Logger LOG = LoggerFactory.getLogger(DBAccessorImpl.class);
  private final DatabasePlatform databasePlatform;
  private final Connection connection;
  private final DbmsHelper dbmsHelper;
  private Configuration configuration;
  private DatabaseMetaData databaseMetaData;
  private static final String dbURLPatternString = "jdbc:(.*?):.*";
  private DbType dbType;
  private final String dbSchema;

  @Inject
  public DBAccessorImpl(Configuration configuration) {
    this.configuration = configuration;

    try {
      Class.forName(configuration.getDatabaseDriver());

      connection = DriverManager.getConnection(configuration.getDatabaseUrl(),
              configuration.getDatabaseUser(),
              configuration.getDatabasePassword());

      connection.setAutoCommit(true); //enable autocommit

      //TODO create own mapping and platform classes for supported databases
      String vendorName = connection.getMetaData().getDatabaseProductName()
              + connection.getMetaData().getDatabaseMajorVersion();
      String dbPlatform = DBPlatformHelper.getDBPlatform(vendorName, new AbstractSessionLog() {
        @Override
        public void log(SessionLogEntry sessionLogEntry) {
          LOG.debug(sessionLogEntry.getMessage());
        }
      });
      databasePlatform = (DatabasePlatform) Class.forName(dbPlatform).newInstance();
      dbmsHelper = loadHelper(databasePlatform);
      dbSchema = convertObjectName(configuration.getDatabaseSchema());
    } catch (Exception e) {
      String message = "";
      if (e instanceof ClassNotFoundException) {
        message = "If you are using a non-default database for Ambari and a custom JDBC driver jar, you need to set property \"server.jdbc.driver.path={path/to/custom_jdbc_driver}\" " +
                "in ambari.properties config file, to include it in ambari-server classpath.";
      } else {
        message = "Error while creating database accessor ";
      }
      LOG.error(message, e);
      throw new RuntimeException(message,e);
    }
  }

  protected DbmsHelper loadHelper(DatabasePlatform databasePlatform) {
    if (databasePlatform instanceof OraclePlatform) {
      dbType = DbType.ORACLE;
      return new OracleHelper(databasePlatform);
    } else if (databasePlatform instanceof MySQLPlatform) {
      dbType = DbType.MYSQL;
      return new MySqlHelper(databasePlatform);
    } else if (databasePlatform instanceof PostgreSQLPlatform) {
      dbType = DbType.POSTGRES;
      return new PostgresHelper(databasePlatform);
    } else if (databasePlatform instanceof DerbyPlatform) {
      dbType = DbType.DERBY;
      return new DerbyHelper(databasePlatform);
    } else {
      dbType = DbType.UNKNOWN;
      return new GenericDbmsHelper(databasePlatform);
    }
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public Connection getNewConnection() {
    try {
      return DriverManager.getConnection(configuration.getDatabaseUrl(),
              configuration.getDatabaseUser(),
              configuration.getDatabasePassword());
    } catch (SQLException e) {
      throw new RuntimeException("Unable to connect to database", e);
    }
  }

  @Override
  public String quoteObjectName(String name) {
    return dbmsHelper.quoteObjectName(name);
  }

  @Override
  public void createTable(String tableName, List<DBColumnInfo> columnInfo,
      String... primaryKeyColumns) throws SQLException {
    // do nothing if the table already exists
    if (tableExists(tableName)) {
      return;
    }

    // guard against null PKs
    primaryKeyColumns = ArrayUtils.nullToEmpty(primaryKeyColumns);

    String query = dbmsHelper.getCreateTableStatement(tableName, columnInfo,
        Arrays.asList(primaryKeyColumns));

    executeQuery(query);
  }

  protected DatabaseMetaData getDatabaseMetaData() throws SQLException {
    if (databaseMetaData == null) {
      databaseMetaData = connection.getMetaData();
    }

    return databaseMetaData;
  }

  private String convertObjectName(String objectName) throws SQLException {
    //tolerate null names for proper usage in filters
    if (objectName == null) {
      return null;
    }
    DatabaseMetaData metaData = getDatabaseMetaData();
    if (metaData.storesLowerCaseIdentifiers()) {
      return objectName.toLowerCase();
    } else if (metaData.storesUpperCaseIdentifiers()) {
      return objectName.toUpperCase();
    }

    return objectName;
  }

  @Override
  public boolean tableExists(String tableName) throws SQLException {
    boolean result = false;
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet res = metaData.getTables(null, dbSchema, convertObjectName(tableName), new String[]{"TABLE"});

    if (res != null) {
      try {
        if (res.next()) {
          result = res.getString("TABLE_NAME") != null && res.getString("TABLE_NAME").equalsIgnoreCase(tableName);
        }
        if (res.next()) {
          throw new IllegalStateException(
                  String.format("Request for table [%s] existing returned more than one results",
                          tableName));
        }
      } finally {
        res.close();
      }
    }

    return result;
  }

  @Override
  public DbType getDbType() {
    return dbType;
  }

  @Override
  public boolean tableHasData(String tableName) throws SQLException {
    String query = "SELECT count(*) from " + tableName;
    Statement statement = getConnection().createStatement();
    boolean retVal = false;
    ResultSet rs = null;
    try {
      rs = statement.executeQuery(query);
      if (rs != null) {
        if (rs.next()) {
          return rs.getInt(1) > 0;
        }
      }
    } catch (Exception e) {
      LOG.error("Unable to check if table " + tableName + " has any data. Exception: " + e.getMessage());
    } finally {
      if (statement != null) {
        statement.close();
      }
      if (rs != null) {
        rs.close();
      }
    }
    return retVal;
  }

  @Override
  public boolean tableHasColumn(String tableName, String columnName) throws SQLException {
    boolean result = false;
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet rs = metaData.getColumns(null, dbSchema, convertObjectName(tableName), convertObjectName(columnName));

    if (rs != null) {
      try {
        if (rs.next()) {
          result = rs.getString("COLUMN_NAME") != null && rs.getString("COLUMN_NAME").equalsIgnoreCase(columnName);
        }
        if (rs.next()) {
          throw new IllegalStateException(
                  String.format("Request for column [%s] existing in table [%s] returned more than one results",
                          columnName, tableName));
        }
      } finally {
        rs.close();
      }
    }

    return result;
  }

  @Override
  public boolean tableHasColumn(String tableName, String... columnName) throws SQLException {
    List<String> columnsList = new ArrayList<String>(Arrays.asList(columnName));
    DatabaseMetaData metaData = getDatabaseMetaData();

    CustomStringUtils.toUpperCase(columnsList);
    Set<String> columnsListToCheckCopies = new HashSet<>(columnsList);
    List<String> duplicatedColumns = new ArrayList<>();
    ResultSet rs = metaData.getColumns(null, dbSchema, convertObjectName(tableName), null);

    if (rs != null) {
      try {
        while (rs.next()) {
          String actualColumnName = rs.getString("COLUMN_NAME");
          if (actualColumnName != null) {
            boolean removingResult = columnsList.remove(actualColumnName.toUpperCase());
            if (!removingResult && columnsListToCheckCopies.contains(actualColumnName.toUpperCase())) {
              duplicatedColumns.add(actualColumnName.toUpperCase());
            }
          }
        }
      } finally {
        rs.close();
      }
    }
    if (!duplicatedColumns.isEmpty()) {
      throw new IllegalStateException(
              String.format("Request for columns [%s] existing in table [%s] returned too many results [%s] for columns [%s]",
                      columnName, tableName, duplicatedColumns.size(), duplicatedColumns.toString()));
    }

    return columnsList.size() == 0;
  }

  @Override
  public boolean tableHasForeignKey(String tableName, String fkName) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet rs = metaData.getImportedKeys(null, dbSchema, convertObjectName(tableName));

    if (rs != null) {
      try {
        while (rs.next()) {
          if (StringUtils.equalsIgnoreCase(fkName, rs.getString("FK_NAME"))) {
            return true;
          }
        }
      } finally {
        rs.close();
      }
    }

    LOG.warn("FK {} not found for table {}", convertObjectName(fkName), convertObjectName(tableName));

    return false;
  }

  public String getCheckedForeignKey(String tableName, String fkName) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    ResultSet rs = metaData.getImportedKeys(null, dbSchema, convertObjectName(tableName));

    if (rs != null) {
      try {
        while (rs.next()) {
          if (StringUtils.equalsIgnoreCase(fkName, rs.getString("FK_NAME"))) {
            return rs.getString("FK_NAME");
          }
        }
      } finally {
        rs.close();
      }
    }

    LOG.warn("FK {} not found for table {}", convertObjectName(fkName), convertObjectName(tableName));

    return null;
  }
  @Override
  public boolean tableHasForeignKey(String tableName, String refTableName,
          String columnName, String refColumnName) throws SQLException {
    return tableHasForeignKey(tableName, refTableName, new String[]{columnName}, new String[]{refColumnName});
  }

  @Override
  public boolean tableHasForeignKey(String tableName, String referenceTableName, String[] keyColumns,
          String[] referenceColumns) throws SQLException {
    DatabaseMetaData metaData = getDatabaseMetaData();

    //NB: reference table contains pk columns while key table contains fk columns
    ResultSet rs = metaData.getCrossReference(null, dbSchema, convertObjectName(referenceTableName),
            null, dbSchema, convertObjectName(tableName));

    List<String> pkColumns = new ArrayList<String>(referenceColumns.length);
    for (String referenceColumn : referenceColumns) {
      pkColumns.add(convertObjectName(referenceColumn));
    }
    List<String> fkColumns = new ArrayList<String>(keyColumns.length);
    for (String keyColumn : keyColumns) {
      fkColumns.add(convertObjectName(keyColumn));
    }

    if (rs != null) {
      try {
        while (rs.next()) {

          String pkColumn = rs.getString("PKCOLUMN_NAME");
          String fkColumn = rs.getString("FKCOLUMN_NAME");

          int pkIndex = pkColumns.indexOf(pkColumn);
          int fkIndex = fkColumns.indexOf(fkColumn);
          if (pkIndex != -1 && fkIndex != -1) {
            if (pkIndex != fkIndex) {
              LOG.warn("Columns for FK constraint should be provided in exact order");
            } else {
              pkColumns.remove(pkIndex);
              fkColumns.remove(fkIndex);
            }

          } else {
            LOG.debug("pkCol={}, fkCol={} not found in provided column names, skipping", pkColumn, fkColumn); //TODO debug
          }

        }
        if (pkColumns.isEmpty() && fkColumns.isEmpty()) {
          return true;
        }

      } finally {
        rs.close();
      }
    }

    return false;

  }

  @Override
  public boolean tableHasIndex(String tableName, boolean unique, String indexName) throws SQLException{
    if (tableExists(tableName)){
      List<String> indexList = getIndexesList(tableName, false);
      return (CustomStringUtils.containsCaseInsensitive(indexName, indexList));
    }
    return false;
  }

  @Override
  public void createIndex(String indexName, String tableName,
          String... columnNames) throws SQLException {
   if (!tableHasIndex(tableName, false, indexName)) {
     String query = dbmsHelper.getCreateIndexStatement(indexName, tableName, columnNames);
     executeQuery(query);
   } else {
     LOG.info("Index {} already exist, skipping creation, table = {}", indexName, tableName);
   }
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String keyColumn, String referenceTableName,
          String referenceColumn, boolean ignoreFailure) throws SQLException {

    addFKConstraint(tableName, constraintName, new String[]{keyColumn}, referenceTableName,
            new String[]{referenceColumn}, false, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String keyColumn, String referenceTableName,
          String referenceColumn, boolean shouldCascadeOnDelete,
          boolean ignoreFailure) throws SQLException {

    addFKConstraint(tableName, constraintName, new String[]{keyColumn}, referenceTableName,
            new String[]{referenceColumn}, shouldCascadeOnDelete, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String[] keyColumns, String referenceTableName,
          String[] referenceColumns,
          boolean ignoreFailure) throws SQLException {
    addFKConstraint(tableName, constraintName, keyColumns, referenceTableName, referenceColumns, false, ignoreFailure);
  }

  @Override
  public void addFKConstraint(String tableName, String constraintName,
          String[] keyColumns, String referenceTableName,
          String[] referenceColumns, boolean shouldCascadeOnDelete,
          boolean ignoreFailure) throws SQLException {
    if (!tableHasForeignKey(tableName, referenceTableName, keyColumns, referenceColumns)) {
      String query = dbmsHelper.getAddForeignKeyStatement(tableName, constraintName,
              Arrays.asList(keyColumns),
              referenceTableName,
              Arrays.asList(referenceColumns),
              shouldCascadeOnDelete);

      try {
        executeQuery(query, ignoreFailure);
      } catch (SQLException e) {
        LOG.warn("Add FK constraint failed"
                + ", constraintName = " + constraintName
                + ", tableName = " + tableName, e.getMessage());
        if (!ignoreFailure) {
          throw e;
        }
      }
    } else {
      LOG.info("Foreign Key constraint {} already exists, skipping", constraintName);
    }
  }

  public boolean tableHasConstraint(String tableName, String constraintName) throws SQLException {
    // this kind of request is well lower level as we querying system tables, due that we need for some the name of catalog.
    String query = dbmsHelper.getTableConstraintsStatement(connection.getCatalog(), tableName);
    Statement statement = null;
    ResultSet rs = null;
    try {
      statement = getConnection().createStatement();
      rs = statement.executeQuery(query);
      if (rs != null) {
        while (rs.next()) {
          if (rs.getString("CONSTRAINT_NAME").equalsIgnoreCase(constraintName)) {
            return true;
          }
        }
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
      if (rs != null) {
        rs.close();
      }
    }
    return false;
  }

  @Override
  public void addUniqueConstraint(String tableName, String constraintName, String... columnNames)
          throws SQLException {
    if (!tableHasConstraint(tableName, constraintName) && tableHasColumn(tableName, columnNames)) {
      String query = dbmsHelper.getAddUniqueConstraintStatement(tableName, constraintName, columnNames);
      try {
        executeQuery(query);
      } catch (SQLException e) {
        LOG.warn("Add unique constraint failed, constraintName={},tableName={}", constraintName, tableName);
        throw e;
      }
    } else {
      LOG.info("Unique constraint {} already exists or columns {} not found, skipping", constraintName, StringUtils.join(columnNames, ", "));
    }
  }

  @Override
  public void addPKConstraint(String tableName, String constraintName, boolean ignoreErrors, String... columnName) throws SQLException {
    if (!tableHasPrimaryKey(tableName, null) && tableHasColumn(tableName, columnName)) {
      String query = dbmsHelper.getAddPrimaryKeyConstraintStatement(tableName, constraintName, columnName);
      executeQuery(query, ignoreErrors);
    } else {
      LOG.warn("Primary constraint {} not altered to table {} as column {} not present or constraint already exists",
              constraintName, tableName, columnName);
    }
  }

  @Override
  public void addPKConstraint(String tableName, String constraintName, String... columnName) throws SQLException {
    addPKConstraint(tableName, constraintName, false, columnName);
  }

  @Override
  public void renameColumn(String tableName, String oldColumnName,
          DBColumnInfo columnInfo) throws SQLException {
    //it is mandatory to specify type in column change clause for mysql
    String renameColumnStatement = dbmsHelper.getRenameColumnStatement(tableName, oldColumnName, columnInfo);
    executeQuery(renameColumnStatement);

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addColumn(String tableName, DBColumnInfo columnInfo) throws SQLException {
    if (tableHasColumn(tableName, columnInfo.getName())) {
      return;
    }

    DatabaseType databaseType = configuration.getDatabaseType();
    switch (databaseType) {
      case ORACLE: {
        // capture the original null value and set the column to nullable if
        // there is a default value
        boolean originalNullable = columnInfo.isNullable();
        if (columnInfo.getDefaultValue() != null) {
          columnInfo.setNullable(true);
        }

        String query = dbmsHelper.getAddColumnStatement(tableName, columnInfo);
        executeQuery(query);

        // update the column after it's been created with the default value and
        // then set the nullable field back to the specified value
        if (columnInfo.getDefaultValue() != null) {
          updateTable(tableName, columnInfo.getName(), columnInfo.getDefaultValue(), "");

          // if the column wasn't originally nullable, then set that here
          if (!originalNullable) {
            setColumnNullable(tableName, columnInfo, originalNullable);
          }

          // finally, add the DEFAULT constraint to the table
          addDefaultConstraint(tableName, columnInfo);
        }
        break;
      }
      case DERBY:
      case MYSQL:
      case POSTGRES:
      case SQL_ANYWHERE:
      case SQL_SERVER:
      default: {
        String query = dbmsHelper.getAddColumnStatement(tableName, columnInfo);
        executeQuery(query);
        break;
      }
    }
  }

  @Override
  public void alterColumn(String tableName, DBColumnInfo columnInfo)
          throws SQLException {
    //varchar extension only (derby limitation, but not too much for others),
    if (dbmsHelper.supportsColumnTypeChange()) {
      String statement = dbmsHelper.getAlterColumnStatement(tableName,
              columnInfo);
      executeQuery(statement);
    } else {
      //use addColumn: add_tmp-update-drop-rename for Derby
      DBColumnInfo columnInfoTmp = new DBColumnInfo(
              columnInfo.getName() + "_TMP",
              columnInfo.getType(),
              columnInfo.getLength());
      String statement = dbmsHelper.getAddColumnStatement(tableName, columnInfoTmp);
      executeQuery(statement);
      updateTable(tableName, columnInfo, columnInfoTmp);
      dropColumn(tableName, columnInfo.getName());
      renameColumn(tableName, columnInfoTmp.getName(), columnInfo);
    }
    if (isColumnNullable(tableName, columnInfo.getName()) != columnInfo.isNullable()) {
      setColumnNullable(tableName, columnInfo, columnInfo.isNullable());
    }
  }

  @Override
  public void updateTable(String tableName, DBColumnInfo columnNameFrom,
          DBColumnInfo columnNameTo) throws SQLException {
    LOG.info("Executing query: UPDATE TABLE " + tableName + " SET "
            + columnNameTo.getName() + "=" + columnNameFrom.getName());

    String statement = "SELECT * FROM " + tableName;
    int typeFrom = getColumnType(tableName, columnNameFrom.getName());
    int typeTo = getColumnType(tableName, columnNameTo.getName());
    Statement dbStatement = null;
    ResultSet rs = null;
    try {
    dbStatement = getConnection().createStatement(ResultSet.TYPE_SCROLL_SENSITIVE,
            ResultSet.CONCUR_UPDATABLE);
    rs = dbStatement.executeQuery(statement);

    while (rs.next()) {
      convertUpdateData(rs, columnNameFrom, typeFrom, columnNameTo, typeTo);
      rs.updateRow();
    }
    } finally {
      if (rs != null) {
        rs.close();
      }
      if (dbStatement != null) {
        dbStatement.close();
      }
    }
  }

  private void convertUpdateData(ResultSet rs, DBColumnInfo columnNameFrom,
          int typeFrom,
          DBColumnInfo columnNameTo, int typeTo) throws SQLException {
    if (typeFrom == Types.BLOB && typeTo == Types.CLOB) {
      //BLOB-->CLOB
      Blob data = rs.getBlob(columnNameFrom.getName());
      if (data != null) {
        rs.updateClob(columnNameTo.getName(),
                new BufferedReader(new InputStreamReader(data.getBinaryStream(), Charset.defaultCharset())));
      }
    } else {
      Object data = rs.getObject(columnNameFrom.getName());
      rs.updateObject(columnNameTo.getName(), data);
    }

  }

  @Override
  public boolean insertRow(String tableName, String[] columnNames, String[] values, boolean ignoreFailure) throws SQLException {
    StringBuilder builder = new StringBuilder();
    builder.append("INSERT INTO ").append(tableName).append("(");
    if (columnNames.length != values.length) {
      throw new IllegalArgumentException("number of columns should be equal to number of values");
    }

    for (int i = 0; i < columnNames.length; i++) {
      builder.append(columnNames[i]);
      if (i != columnNames.length - 1) {
        builder.append(",");
      }
    }

    builder.append(") VALUES(");

    for (int i = 0; i < values.length; i++) {
      builder.append(values[i]);
      if (i != values.length - 1) {
        builder.append(",");
      }
    }

    builder.append(")");

    Statement statement = getConnection().createStatement();
    int rowsUpdated = 0;
    String query = builder.toString();
    try {
      rowsUpdated = statement.executeUpdate(query);
    } catch (SQLException e) {
      LOG.warn("Unable to execute query: " + query, e);
      if (!ignoreFailure) {
        throw e;
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }

    return rowsUpdated != 0;
  }

  @Override
  public boolean insertRowIfMissing(String tableName, String[] columnNames, String[] values, boolean ignoreFailure) throws SQLException {
    if (columnNames.length == 0) {
      return false;
    }

    if (columnNames.length != values.length) {
      throw new IllegalArgumentException("number of columns should be equal to number of values");
    }

    StringBuilder builder = new StringBuilder();
    builder.append("SELECT COUNT(*) FROM ").append(tableName);

    builder.append(" WHERE ").append(columnNames[0]).append("=").append(values[0]);
    for (int i = 1; i < columnNames.length; i++) {
      builder.append(" AND ").append(columnNames[i]).append("=").append(values[i]);
    }

    Statement statement = getConnection().createStatement();
    ResultSet resultSet = null;
    int count = -1;
    String query = builder.toString();
    try {
      resultSet = statement.executeQuery(query);

      if ((resultSet != null) && (resultSet.next())) {
        count = resultSet.getInt(1);
      }
    } catch (SQLException e) {
      LOG.warn("Unable to execute query: " + query, e);
      if (!ignoreFailure) {
        throw e;
      }
    } finally {
      if (resultSet != null) {
        resultSet.close();
      }
      if (statement != null) {
        statement.close();
      }
    }

    return (count == 0) && insertRow(tableName, columnNames, values, ignoreFailure);
  }

  @Override
  public int updateTable(String tableName, String columnName, Object value,
          String whereClause) throws SQLException {

    StringBuilder query = new StringBuilder(String.format("UPDATE %s SET %s = ", tableName, columnName));
    query.append(escapeParameter(value));
    query.append(" ");
    query.append(whereClause);

    Statement statement = getConnection().createStatement();
    int res = -1;
    try {
      res = statement.executeUpdate(query.toString());
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
    return res;
  }

  @Override
  public int executeUpdate(String query) throws SQLException {
    return executeUpdate(query, false);
  }

  @Override
  public int executeUpdate(String query, boolean ignoreErrors) throws SQLException {
    Statement statement = getConnection().createStatement();
    try {
      return statement.executeUpdate(query);
    } catch (SQLException e) {
      LOG.warn("Error executing query: " + query + ", "
              + "errorCode = " + e.getErrorCode() + ", message = " + e.getMessage());
      if (!ignoreErrors) {
        throw e;
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
    return 0;  // If error appears and ignoreError is set, return 0 (no changes was made)
  }

  @Override
  public void executeQuery(String query, String tableName, String hasColumnName) throws SQLException {
    if (tableHasColumn(tableName, hasColumnName)) {
      executeQuery(query);
    }
  }

  @Override
  public void executeQuery(String query) throws SQLException {
    executeQuery(query, false);
  }

  @Override
  public void executeQuery(String query, boolean ignoreFailure) throws SQLException {
    LOG.info("Executing query: {}", query);
    Statement statement = getConnection().createStatement();
    try {
      statement.execute(query);
    } catch (SQLException e) {
      if (!ignoreFailure) {
        LOG.error("Error executing query: " + query, e);
        throw e;
      } else {
        LOG.warn("Error executing query: " + query + ", "
                + "errorCode = " + e.getErrorCode() + ", message = " + e.getMessage());
      }
    } finally {
      if (statement != null) {
        statement.close();
      }
    }
  }

  @Override
  public void dropTable(String tableName) throws SQLException {
    String query = dbmsHelper.getDropTableStatement(tableName);
    executeQuery(query);
  }


  @Override
  public void truncateTable(String tableName) throws SQLException {
    String query = "DELETE FROM " + tableName;
    executeQuery(query);
  }

  @Override
  public void dropColumn(String tableName, String columnName) throws SQLException {
    if (tableHasColumn(tableName, columnName)) {
      String query = dbmsHelper.getDropTableColumnStatement(tableName, columnName);
      executeQuery(query);
    }
  }

  @Override
  public void dropSequence(String sequenceName) throws SQLException {
    executeQuery(dbmsHelper.getDropSequenceStatement(sequenceName), true);
  }

  @Override
  public void dropFKConstraint(String tableName, String constraintName) throws SQLException {
    dropFKConstraint(tableName, constraintName, false);
  }

  @Override
  public void dropFKConstraint(String tableName, String constraintName, boolean ignoreFailure) throws SQLException {
    // ToDo: figure out if name of index and constraint differs
    String checkedConstraintName = getCheckedForeignKey(convertObjectName(tableName), constraintName);
    if (checkedConstraintName != null) {
      String query = dbmsHelper.getDropFKConstraintStatement(tableName, checkedConstraintName);
      executeQuery(query, ignoreFailure);

      // MySQL also adds indexes in addition to the FK which should be dropped
      Configuration.DatabaseType databaseType = configuration.getDatabaseType();
      if (databaseType == DatabaseType.MYSQL) {
        query = dbmsHelper.getDropIndexStatement(constraintName, tableName);
        executeQuery(query, true);
      }

    } else {
      LOG.warn("Constraint {} from {} table not found, nothing to drop", constraintName, tableName);
    }
  }

  @Override
  public void dropUniqueConstraint(String tableName, String constraintName, boolean ignoreFailure) throws SQLException {
    if (tableHasConstraint(convertObjectName(tableName), convertObjectName(constraintName))) {
      String query = dbmsHelper.getDropUniqueConstraintStatement(tableName, constraintName);
      executeQuery(query, ignoreFailure);
    } else {
      LOG.warn("Unique constraint {} from {} table not found, nothing to drop", constraintName, tableName);
    }
  }

  @Override
  public void dropUniqueConstraint(String tableName, String constraintName) throws SQLException {
    dropUniqueConstraint(tableName, constraintName, false);
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName, String columnName, boolean cascade) throws SQLException {
    if (tableHasPrimaryKey(tableName, columnName)) {
      String query = dbmsHelper.getDropPrimaryKeyStatement(convertObjectName(tableName), constraintName, cascade);
      executeQuery(query, false);
    } else {
      LOG.warn("Primary key doesn't exists for {} table, skipping", tableName);
    }
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName, boolean ignoreFailure, boolean cascade) throws SQLException {
    /*
     * Note, this is un-safe implementation as constraint name checking will work only for PostgresSQL,
     * MySQL and Oracle doesn't use constraint name for drop primary key
     * Consider to use implementation with column name checking for existed constraint.
     */
    if (tableHasPrimaryKey(tableName, null)) {
      String query = dbmsHelper.getDropPrimaryKeyStatement(convertObjectName(tableName), constraintName, cascade);
      executeQuery(query, ignoreFailure);
    } else {
      LOG.warn("Primary key doesn't exists for {} table, skipping", tableName);
    }
  }

  @Override
  public void dropPKConstraint(String tableName, String constraintName, boolean cascade) throws SQLException {
    dropPKConstraint(tableName, constraintName, false, cascade);
  }

  @Override
  /**
   * Execute script with autocommit and error tolerance, like psql and sqlplus
   * do by default
   */
  public void executeScript(String filePath) throws SQLException, IOException {
    BufferedReader br = new BufferedReader(new FileReader(filePath));
    try {
      ScriptRunner scriptRunner = new ScriptRunner(getConnection(), false, false);
      scriptRunner.runScript(br);
    } finally {
      if (br != null) {
        br.close();
      }
    }
  }

  @Override
  public DatabaseSession getNewDatabaseSession() {
    DatabaseLogin login = new DatabaseLogin();
    login.setUserName(configuration.getDatabaseUser());
    login.setPassword(configuration.getDatabasePassword());
    login.setDatasourcePlatform(databasePlatform);
    login.setDatabaseURL(configuration.getDatabaseUrl());
    login.setDriverClassName(configuration.getDatabaseDriver());

    return new DatabaseSessionImpl(login);
  }

  @Override
  public boolean tableHasPrimaryKey(String tableName, String columnName) throws SQLException {
    ResultSet rs = getDatabaseMetaData().getPrimaryKeys(null, dbSchema, convertObjectName(tableName));
    boolean res = false;
    try {
      if (rs != null && columnName != null) {
        while (rs.next()) {
          if (rs.getString("COLUMN_NAME").equalsIgnoreCase(columnName)) {
            res = true;
            break;
          }
        }
      } else if (rs != null) {
        res = rs.next();
      }
    } finally {
      if (rs != null) {
        rs.close();
      }
    }
    return res;
  }

  @Override
  public int getColumnType(String tableName, String columnName)
          throws SQLException {
    // We doesn't require any actual result except metadata, so WHERE clause shouldn't match
    int res;
    String query;
    Statement statement = null;
    ResultSet rs = null;
    ResultSetMetaData rsmd = null;
    try {
    query = String.format("SELECT %s FROM %s WHERE 1=2", columnName, convertObjectName(tableName));
    statement = getConnection().createStatement();
    rs = statement.executeQuery(query);
    rsmd = rs.getMetaData();
    res = rsmd.getColumnType(1);
    } finally {
      if (rs != null){
        rs.close();
      }
      if (statement != null) {
        statement.close();
      }
    }
    return res;
  }

  @Override
  public Class getColumnClass(String tableName, String columnName)
          throws SQLException, ClassNotFoundException {
    // We doesn't require any actual result except metadata, so WHERE clause shouldn't match
    String query = String.format("SELECT %s FROM %s WHERE 1=2", convertObjectName(columnName), convertObjectName(tableName));
    Statement statement = null;
    ResultSet rs = null;
    try {
      statement = getConnection().createStatement();
      rs = statement.executeQuery(query);
      return Class.forName(rs.getMetaData().getColumnClassName(1));
    } finally {
      if (statement != null) {
        statement.close();
      }
      if (rs != null) {
        rs.close();
      }
    }
  }

  @Override
  public boolean isColumnNullable(String tableName, String columnName) throws SQLException {
    // We doesn't require any actual result except metadata, so WHERE clause shouldn't match
    String query = String.format("SELECT %s FROM %s WHERE 1=2", convertObjectName(columnName), convertObjectName(tableName));
    Statement statement = null;
    ResultSet rs = null;
    try {
      statement = getConnection().createStatement();
      rs = statement.executeQuery(query);
      return !(rs.getMetaData().isNullable(1) == ResultSetMetaData.columnNoNulls);
    } finally {
      if (statement != null) {
        statement.close();
      }
      if (rs != null) {
        rs.close();
      }
    }
  }

  @Override
  public void setColumnNullable(String tableName, DBAccessor.DBColumnInfo columnInfo, boolean nullable)
          throws SQLException {
    String columnName = columnInfo.getName();

    // if column is already in nullable state, we shouldn't do anything. This is important for Oracle
    if (isColumnNullable(tableName, columnName) != nullable) {
      String query = dbmsHelper.getSetNullableStatement(tableName, columnInfo, nullable);
      executeQuery(query);
    } else {
      LOG.info("Column nullability property is not changed due to {} column from {} table is already in {} state, skipping",
                columnName, tableName, (nullable) ? "nullable" : "not nullable");
    }
  }

  @Override
  public void setColumnNullable(String tableName, String columnName, boolean nullable)
          throws SQLException {
    try {
      Class columnClass = getColumnClass(tableName, columnName);
      setColumnNullable(tableName,new DBColumnInfo(columnName, columnClass), nullable);
    } catch (ClassNotFoundException e) {
      LOG.error("Could not modify table=[], column={}, error={}", tableName, columnName, e.getMessage());
    }
  }

  @Override
  public void changeColumnType(String tableName, String columnName, Class fromType, Class toType) throws SQLException {
    // ToDo: create column with more random name
    String tempColumnName = columnName + "_temp";

    switch (configuration.getDatabaseType()) {
      case ORACLE:
        if (String.class.equals(fromType)
                && (toType.equals(Character[].class))
                || toType.equals(char[].class)) {
          addColumn(tableName, new DBColumnInfo(tempColumnName, toType));
          executeUpdate(String.format("UPDATE %s SET %s = %s", convertObjectName(tableName),
                  convertObjectName(tempColumnName), convertObjectName(columnName)));
          dropColumn(tableName, columnName);
          renameColumn(tableName, tempColumnName, new DBColumnInfo(columnName, toType));
          return;
        }
        break;
    }

    alterColumn(tableName, new DBColumnInfo(columnName, toType, null));
  }

  @Override
  public List<String> getIndexesList(String tableName, boolean unique)
    throws SQLException{
    ResultSet rs = getDatabaseMetaData().getIndexInfo(null, dbSchema, convertObjectName(tableName), unique, false);
    List<String> indexList = new ArrayList<String>();
    if (rs != null){
      try{
        while (rs.next()) {
          String indexName = rs.getString(convertObjectName("index_name"));
          if (indexName != null) {  // hack for Oracle database, as she could return null values
            indexList.add(indexName);
          }
        }
      }finally {
        rs.close();
      }
    }
    return indexList;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getPrimaryKeyConstraintName(String tableName) throws SQLException {
    String primaryKeyConstraintName = null;
    Statement statement = null;
    ResultSet resultSet = null;
    Configuration.DatabaseType databaseType = configuration.getDatabaseType();

    switch (databaseType) {
      case ORACLE: {
        String lookupPrimaryKeyNameSql = String.format(
            "SELECT constraint_name FROM all_constraints WHERE UPPER(table_name) = UPPER('%s') AND constraint_type = 'P'",
            tableName);

        try {
          statement = getConnection().createStatement();
          resultSet = statement.executeQuery(lookupPrimaryKeyNameSql);
          if (resultSet.next()) {
            primaryKeyConstraintName = resultSet.getString("constraint_name");
          }
        } finally {
          JdbcUtils.closeResultSet(resultSet);
          JdbcUtils.closeStatement(statement);
        }

        break;
      }
      case SQL_SERVER: {
        String lookupPrimaryKeyNameSql = String.format(
            "SELECT constraint_name FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE OBJECTPROPERTY(OBJECT_ID(constraint_name), 'IsPrimaryKey') = 1 AND table_name = '%s'",
            tableName);

        try {
          statement = getConnection().createStatement();
          resultSet = statement.executeQuery(lookupPrimaryKeyNameSql);
          if (resultSet.next()) {
            primaryKeyConstraintName = resultSet.getString("constraint_name");
          }
        } finally {
          JdbcUtils.closeResultSet(resultSet);
          JdbcUtils.closeStatement(statement);
        }

        break;
      }
      case POSTGRES: {
        String lookupPrimaryKeyNameSql = String.format(
            "SELECT constraint_name FROM information_schema.table_constraints AS tc WHERE tc.constraint_type = 'PRIMARY KEY' AND table_name = '%s'",
            tableName);

        try {
          statement = getConnection().createStatement();
          resultSet = statement.executeQuery(lookupPrimaryKeyNameSql);
          if (resultSet.next()) {
            primaryKeyConstraintName = resultSet.getString("constraint_name");
          }
        } finally {
          JdbcUtils.closeResultSet(resultSet);
          JdbcUtils.closeStatement(statement);
        }

        break;
      }
      default:
        break;
    }

    return primaryKeyConstraintName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void dropPKConstraint(String tableName, String defaultConstraintName) throws SQLException {
    Configuration.DatabaseType databaseType = configuration.getDatabaseType();

    // drop the PK directly if MySQL since it supports it
    if (databaseType == DatabaseType.MYSQL) {
      String mysqlDropQuery = String.format("ALTER TABLE %s DROP PRIMARY KEY", tableName);
      executeQuery(mysqlDropQuery, true);
      return;
    }

    // discover the PK name, using the default if none found
    String primaryKeyConstraintName = getPrimaryKeyConstraintName(tableName);
    if (null == primaryKeyConstraintName) {
      primaryKeyConstraintName = defaultConstraintName;
      LOG.warn("Unable to dynamically determine the PK constraint name for {}, defaulting to {}",
          tableName, defaultConstraintName);
    }

    // warn if we can't find it
    if (null == primaryKeyConstraintName) {
      LOG.warn("Unable to determine the primary key constraint name for {}", tableName);
    } else {
      dropPKConstraint(tableName, primaryKeyConstraintName, true);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addDefaultConstraint(String tableName, DBColumnInfo column) throws SQLException {
    String defaultValue = escapeParameter(column.getDefaultValue());
    StringBuilder builder = new StringBuilder(String.format("ALTER TABLE %s ", tableName));

    DatabaseType databaseType = configuration.getDatabaseType();
    switch (databaseType) {
      case DERBY:
      case MYSQL:
      case POSTGRES:
      case SQL_ANYWHERE:
        builder.append(String.format("ALTER %s SET DEFAULT %s", column.getName(), defaultValue));
        break;
      case ORACLE:
        builder.append(String.format("MODIFY %s DEFAULT %s", column.getName(), defaultValue));
        break;
      case SQL_SERVER:
        builder.append(
            String.format("ALTER COLUMN %s SET DEFAULT %s", column.getName(), defaultValue));
        break;
      default:
        builder.append(String.format("ALTER %s SET DEFAULT %s", column.getName(), defaultValue));
        break;
    }

    executeQuery(builder.toString());
  }

  /**
   * Gets an escaped version of the specified value suitable for including as a
   * parameter when building statements.
   *
   * @param value
   *          the value to escape
   * @return the escaped value
   */
  protected String escapeParameter(Object value) {
    // Only String and number supported.
    // Taken from:
    // org.eclipse.persistence.internal.databaseaccess.appendParameterInternal
    Object dbValue = databasePlatform.convertToDatabaseType(value);
    String valueString = value.toString();
    if (dbValue instanceof String) {
      valueString = "'" + value.toString() + "'";
    }

    return valueString;
  }
}
