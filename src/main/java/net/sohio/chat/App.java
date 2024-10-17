package net.sohio.chat;

import java.nio.file.Path;
import java.sql.SQLException;

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.DatabaseAdaptor;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.JDBCSessionDataStoreFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.HostPort;

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
        dbConfig.addDataSourceProperty("url", "jdbc:pgsql:schat?unixsocket=/var/run/postgresql");
        dbConfig.addDataSourceProperty("user", "schat");
        dbConfig.addDataSourceProperty("password", "bruhmoment");
        return new HikariDataSource(dbConfig);
    }

    public static void main(String[] args) throws Exception {
        io.netty.channel.epoll.Epoll.ensureAvailability();

        var server = new Server();
        var httpConfiguration = new HttpConfiguration();
        httpConfiguration.setServerAuthority(new HostPort("chat.sohio.net"));
        var httpConnectionFactory = new HttpConnectionFactory(httpConfiguration);

        if (System.getProperty("net.sohio.chat.devPort") instanceof String port) {
            var connector = new ServerConnector(
                server,
                httpConnectionFactory);
            connector.setPort(Integer.parseInt(port));
            server.addConnector(connector);
        }
        
        if (System.getProperty("net.sohio.chat.h2cPath") instanceof String path) {
            var connector = new UnixDomainServerConnector(
                server,
                httpConnectionFactory,
                new HTTP2CServerConnectionFactory(httpConfiguration));
            connector.setUnixDomainPath(Path.of(path));
            server.addConnector(connector);
        }
        
        if (server.getConnectors().length == 0) {
            throw new RuntimeException("set a dev server port or an h2c path");
        }

        var ds = configureDatabase();

        var listenerCon = ds.getConnection();  
        listenerCon.setAutoCommit(true);
        try (var stmt = listenerCon.prepareStatement("LISTEN messages")) {
            stmt.execute();
        }
        
        AtomicSnowflake counter;
        try (var con = ds.getConnection()) {
            con.setAutoCommit(true);
            try (var stmt = con.prepareStatement("SELECT id FROM messages ORDER BY id DESC LIMIT 1")) {
                var rs = stmt.executeQuery();
                counter = new AtomicSnowflake(new Snowflake(rs.getLong("id")));
            }
        } catch (SQLException ex) {
            counter = new AtomicSnowflake();
        }

        var chatHandler = new ContextHandler(
            ChatHandler.from(
                server,
                ds,
                listenerCon.unwrap(PGConnection.class),
                counter),
            "/chat");
        chatHandler.setAllowNullPathInContext(true);
        server.setHandler(new ContextHandlerCollection(chatHandler));

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
