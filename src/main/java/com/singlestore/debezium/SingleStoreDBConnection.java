package com.singlestore.debezium;


import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.ColumnId;
import io.debezium.relational.TableId;
import io.debezium.util.Strings;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static io.debezium.config.CommonConnectorConfig.DATABASE_CONFIG_PREFIX;
import static io.debezium.config.CommonConnectorConfig.DRIVER_CONFIG_PREFIX;

/**
 * {@link JdbcConnection} extension to be used with SingleStoreDB
 */
public class SingleStoreDBConnection extends JdbcConnection {

    private static final String QUOTED_CHARACTER = "`";
    protected static final String URL_PATTERN = "jdbc:singlestore://${hostname}:${port}/?connectTimeout=${connectTimeout}";

    private final SingleStoreDBConnectionConfiguration connectionConfig;

    public SingleStoreDBConnection(SingleStoreDBConnectionConfiguration connectionConfig) {
        super(connectionConfig.jdbcConfig, connectionConfig.factory, SingleStoreDBConnection::validateServerVersion, QUOTED_CHARACTER, QUOTED_CHARACTER);
        this.connectionConfig = connectionConfig;
    }

    private static void validateServerVersion(Statement statement) throws SQLException {
        DatabaseMetaData metaData = statement.getConnection().getMetaData();
        int majorVersion = metaData.getDatabaseMajorVersion();
        int minorVersion = metaData.getDatabaseMinorVersion();
        if (majorVersion < 8 || (majorVersion == 8 && minorVersion < 5)) {
            throw new SQLException("CDC feature is not supported in a version of SingleStore lower than 8.5");
        }
    }

    /**
     * Executes OBSERVE query for CDC output stream events.
     *
     * @param tableFilter       tables filter to observe
     * @param resultSetConsumer the consumer of the query results
     * @return this object for chaining methods together
     * @throws SQLException if there is an error connecting to the database or executing the statements
     */
    public JdbcConnection observe(Set<TableId> tableFilter, ResultSetConsumer resultSetConsumer) throws SQLException {
        return observe(null, tableFilter, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), resultSetConsumer);
    }

    /**
     * Executes OBSERVE query for CDC output stream events.
     *
     * @param fieldFilter       columns filter to observe
     * @param tableFilter       tables filter to observe
     * @param resultSetConsumer the consumer of the query results
     * @return this object for chaining methods together
     * @throws SQLException if there is an error connecting to the database or executing the statements
     */
    public JdbcConnection observe(Set<ColumnId> fieldFilter, Set<TableId> tableFilter, ResultSetConsumer resultSetConsumer) throws SQLException {
        return observe(fieldFilter, tableFilter, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), resultSetConsumer);
    }

    /**
     * Executes OBSERVE query for CDC output stream events.
     *
     * @param fieldFilter       columns filter to observe
     * @param tableFilter       tables filter to observe
     * @param format            output format(SQL | JSON)
     * @param outputConfig      FS <FsConfig> | S3 <S3Config> | GCS <GCSConfig>
     * @param offSetConfig      offset config (<offset> | NULL),+    // # of partitions
     * @param recordFilter      filter on record metadata or content
     * @param resultSetConsumer the consumer of the query results
     * @return this object for chaining methods together
     * @throws SQLException if there is an error connecting to the database or executing the statements
     */
    public JdbcConnection observe(Set<ColumnId> fieldFilter, Set<TableId> tableFilter, Optional<OBSERVE_OUTPUT_FORMAT> format,
                                  Optional<String> outputConfig, Optional<String> offSetConfig, Optional<String> recordFilter,
                                  ResultSetConsumer resultSetConsumer) throws SQLException {
        StringBuilder query = new StringBuilder("OBSERVE ");
        if (fieldFilter != null && !fieldFilter.isEmpty()) {
            query.append(fieldFilter.stream().map(this::quotedColumnIdString)
                    .collect(Collectors.joining(","))).append(" FROM ");
        } else {
            query.append("* FROM ");
        }
        if (tableFilter != null && !tableFilter.isEmpty()) {
            query.append(tableFilter.stream().map(this::quotedTableIdString).collect(Collectors.joining(",")));
        } else {
            query.append("*");
        }
        format.ifPresent(f -> query.append(" AS ").append(f.name()));
        outputConfig.ifPresent(c -> query.append(" INTO ").append(c));
        offSetConfig.ifPresent(o -> query.append(" BEGINNING AT ").append(o));
        recordFilter.ifPresent(f -> query.append(" WHERE ").append(f));
        return query(query.toString(), resultSetConsumer);
    }

    public SingleStoreDBConnectionConfiguration connectionConfig() {
        return connectionConfig;
    }

    public String connectionString() {
        return connectionString(URL_PATTERN);
    }

    @Override
    public String quotedTableIdString(TableId tableId) {
        return tableId.toQuotedString('`');
    }

    public String quotedColumnIdString(ColumnId columnId) {
        String columnName = columnId.columnName();
        char quotingChar = '`';
        if (columnName != null) {
            if (columnName.isEmpty()) {
                columnName = new StringBuilder().append(quotingChar).append(quotingChar).toString();
            } else if (columnName.charAt(0) != quotingChar && columnName.charAt(columnName.length() - 1) != quotingChar) {
                columnName = columnName.replace("" + quotingChar, "" + quotingChar + quotingChar);
                columnName = quotingChar + columnName + quotingChar;
            }
        }
        return quotedTableIdString(columnId.tableId()) + "." + columnName;
    }

    @Override
    protected String[] supportedTableTypes() {
        return new String[]{"TABLE"};
    }

    public enum OBSERVE_OUTPUT_FORMAT {
        SQL, JSON;
    }

    public static class SingleStoreDBConnectionConfiguration {

        private final JdbcConfiguration jdbcConfig;
        private final ConnectionFactory factory;
        private final Configuration config;

        public SingleStoreDBConnectionConfiguration(Configuration config) {
            this.config = config;
            final boolean useSSL = sslModeEnabled();
            final Configuration dbConfig = config
                    .edit()
                    .withDefault(SingleStoreDBConnectorConfig.PORT, SingleStoreDBConnectorConfig.PORT.defaultValue())
                    .build()
                    .subset(DATABASE_CONFIG_PREFIX, true)
                    .merge(config.subset(DRIVER_CONFIG_PREFIX, true));

            final Configuration.Builder jdbcConfigBuilder = dbConfig
                    .edit()
                    .with("connectTimeout", Long.toString(getConnectionTimeout().toMillis()))
                    .with("sslMode", sslMode().getValue())
                    .with("defaultFetchSize", 1)
                    .without("parameters");
            if (useSSL) {
                if (!Strings.isNullOrBlank(sslTrustStore())) {
                    jdbcConfigBuilder.with("trustStore", "file:" + sslTrustStore());
                }
                if (sslTrustStorePassword() != null) {
                    jdbcConfigBuilder.with("trustStorePassword", String.valueOf(sslTrustStorePassword()));
                }
                if (!Strings.isNullOrBlank(sslKeyStore())) {
                    jdbcConfigBuilder.with("keyStore", "file:" + sslKeyStore());
                }
                if (sslKeyStorePassword() != null) {
                    jdbcConfigBuilder.with("keyStorePassword", String.valueOf(sslKeyStorePassword()));
                }
                if (!Strings.isNullOrBlank(sslServerCertificate())) {
                    jdbcConfigBuilder.with("serverSslCert", "file:" + sslServerCertificate());
                }
            }
            driverParameters().forEach(jdbcConfigBuilder::with);
            this.jdbcConfig = JdbcConfiguration.adapt(jdbcConfigBuilder.build());
            factory = JdbcConnection.patternBasedFactory(SingleStoreDBConnection.URL_PATTERN, com.singlestore.jdbc.Driver.class.getName(), getClass().getClassLoader());
        }

        public JdbcConfiguration config() {
            return jdbcConfig;
        }

        public Configuration originalConfig() {
            return config;
        }

        public ConnectionFactory factory() {
            return factory;
        }

        public String username() {
            return config.getString(SingleStoreDBConnectorConfig.USER);
        }

        public String password() {
            return config.getString(SingleStoreDBConnectorConfig.PASSWORD);
        }

        public String hostname() {
            return config.getString(SingleStoreDBConnectorConfig.HOSTNAME);
        }

        public int port() {
            return config.getInteger(SingleStoreDBConnectorConfig.PORT);
        }

        public SingleStoreDBConnectorConfig.SecureConnectionMode sslMode() {
            String mode = config.getString(SingleStoreDBConnectorConfig.SSL_MODE);
            return SingleStoreDBConnectorConfig.SecureConnectionMode.parse(mode);
        }

        public boolean sslModeEnabled() {
            return sslMode() != SingleStoreDBConnectorConfig.SecureConnectionMode.DISABLE;
        }

        public String sslKeyStore() {
            return config.getString(SingleStoreDBConnectorConfig.SSL_KEYSTORE);
        }

        public char[] sslKeyStorePassword() {
            String password = config.getString(SingleStoreDBConnectorConfig.SSL_KEYSTORE_PASSWORD);
            return Strings.isNullOrBlank(password) ? null : password.toCharArray();
        }

        public String sslTrustStore() {
            return config.getString(SingleStoreDBConnectorConfig.SSL_TRUSTSTORE);
        }

        public char[] sslTrustStorePassword() {
            String password = config.getString(SingleStoreDBConnectorConfig.SSL_TRUSTSTORE_PASSWORD);
            return Strings.isNullOrBlank(password) ? null : password.toCharArray();
        }

        public String sslServerCertificate() {
            return config.getString(SingleStoreDBConnectorConfig.SSL_SERVER_CERT);
        }

        public Duration getConnectionTimeout() {
            return Duration.ofMillis(config.getLong(SingleStoreDBConnectorConfig.CONNECTION_TIMEOUT_MS));
        }

        public Map<String, String> driverParameters() {
            final String driverParametersString = config.getString(SingleStoreDBConnectorConfig.DRIVER_PARAMETERS);
            return driverParametersString == null ? Collections.emptyMap() :
                    Arrays.stream(driverParametersString.split(";"))
                            .map(s -> s.split("=")).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim()));
        }

        public CommonConnectorConfig.EventProcessingFailureHandlingMode eventProcessingFailureHandlingMode() {
            String mode = config.getString(CommonConnectorConfig.EVENT_PROCESSING_FAILURE_HANDLING_MODE);
            return CommonConnectorConfig.EventProcessingFailureHandlingMode.parse(mode);
        }

        public CommonConnectorConfig.EventProcessingFailureHandlingMode inconsistentSchemaHandlingMode() {
            String mode = config.getString(SingleStoreDBConnectorConfig.INCONSISTENT_SCHEMA_HANDLING_MODE);
            return CommonConnectorConfig.EventProcessingFailureHandlingMode.parse(mode);
        }
    }
}
