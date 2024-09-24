package net.sohio.chat;

import java.sql.SQLException;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.session.DatabaseAdaptor;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.JDBCSessionDataStoreFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;

import com.impossibl.postgres.api.jdbc.PGConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Hello world!
 */
public class App {
    private static HikariDataSource configureDatabase() {
        var dbConfig = new HikariConfig();
        dbConfig.setDataSourceClassName("com.impossibl.postgres.jdbc.PGDataSource");
        dbConfig.addDataSourceProperty("serverName", "localhost");
        dbConfig.addDataSourceProperty("databaseName", "schat");
        dbConfig.addDataSourceProperty("user", "postgres");
        dbConfig.addDataSourceProperty("password", "bruhmoment");
        return new HikariDataSource(dbConfig);
    }

    public static void main(String[] args) throws Exception {
        var server = new Server(8000);

        var ds = configureDatabase();

        var listenerCon = ds.getConnection();
        
        try (var stmt = listenerCon.createStatement()) {
            stmt.execute("LISTEN messages");
        }
        
        AtomicSnowflake counter;
        try (var con = ds.getConnection()) {
            try (var stmt = con.createStatement()) {
                var rs = stmt.executeQuery("SELECT id FROM messages ORDER BY id DESC LIMIT 1");
                counter = new AtomicSnowflake(new Snowflake(rs.getLong("id")));
            }
        } catch (SQLException ex) {
            counter = new AtomicSnowflake();
        }

        server.setHandler(new GzipHandler(new ContextHandlerCollection(
            new ContextHandler(
                ChatHandler.from(
                    server,
                    ds,
                    listenerCon.unwrap(PGConnection.class),
                    counter),
    "/chat"))));

        var idMgr = new DefaultSessionIdManager(server);
        server.addBean(idMgr, true);

        var cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setRemoveUnloadableSessions(true);
        server.addBean(cacheFactory);

        var dbAdaptor = new DatabaseAdaptor();
        dbAdaptor.setDatasource(ds);
        var datastoreFactory = new JDBCSessionDataStoreFactory();
        datastoreFactory.setDatabaseAdaptor(dbAdaptor);
        server.addBean(datastoreFactory);

        server.start();
    }
}
