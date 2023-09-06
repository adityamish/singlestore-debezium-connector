package com.singlestore.debezium;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.util.Collect;

public class SingleStoreDBErrorHandler extends ErrorHandler {
    
    public SingleStoreDBErrorHandler(SingleStoreDBConnectorConfig connectorConfig, ChangeEventQueue<?> queue) {
        super(SingleStoreDBConnector.class, connectorConfig, queue, null);
    }

    @Override
    protected Set<Class<? extends Exception>> communicationExceptions() {
        return Collect.unmodifiableSet(IOException.class, SQLException.class);
    }

}
