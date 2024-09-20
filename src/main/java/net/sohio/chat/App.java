package net.sohio.chat;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Hello world!
 */
public class App {
    private static DataSource configureDatabase() {
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
        
        AtomicSnowflake counter;
        try (var con = ds.getConnection()) {
            try (var stmt = con.createStatement()) {
                var rs = stmt.executeQuery("SELECT id FROM messages ORDER BY id DESC LIMIT 1");
                counter = new AtomicSnowflake(new Snowflake(rs.getLong("id")));
            }
        } catch (SQLException ex) {
            counter = new AtomicSnowflake();
        }

        server.setHandler(new ContextHandlerCollection(
            new ContextHandler(ChatHandler.from(server, ds, counter), "/chat")
        ));

        server.start();
    }
}
