package net.sohio.chat;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import com.google.gson.Gson;
import com.impossibl.postgres.api.jdbc.PGConnection;
import com.impossibl.postgres.api.jdbc.PGNotificationListener;

public class ChatHandler extends Handler.Abstract {
    private DataSource ds;
    private AtomicSnowflake counter;

    private static ITemplateEngine templateEngine = buildTemplateEngine();

    private ChatHandler(DataSource ds, AtomicSnowflake counter) {
        this.ds = ds;
        this.counter = counter;
    }

    public static Handler from(Server server, DataSource ds, AtomicSnowflake counter) {
        var ws = WebSocketUpgradeHandler.from(server, container -> {
            container.addMapping("/ws", (req, res, cb) -> {
                return new WebSocketHandler(ds);
            });
        });
        ws.setHandler(new ChatHandler(ds, counter));
        return ws;
    }

    private static ITemplateEngine buildTemplateEngine() {
        var resolver = new FileTemplateResolver();

        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setPrefix("./chat/");
        resolver.setSuffix(".html");
        resolver.setCacheable(false);

        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @WebSocket
    public static class WebSocketHandler {
        private static record IncomingMessage(String after) {
        }

        private static Gson gson = new Gson();
        private static PGConnection listenCon = null;

        private DataSource ds;
        private Session session;
        private PGNotificationListener listener = null;

        public WebSocketHandler(DataSource ds) throws SQLException {
            this.ds = ds;

            if (listenCon == null) {
                synchronized (WebSocketHandler.class) {
                    if (listenCon == null) {
                        listenCon = ds.getConnection().unwrap(PGConnection.class);
                        listenCon.setAutoCommit(true);
                        try (var stmt = listenCon.createStatement()) {
                            stmt.execute("LISTEN messages");
                        }
                    }
                }
            }
        }

        @OnWebSocketOpen
        public void open(Session session) {
            this.session = session;
            session.setIdleTimeout(Duration.ZERO);
        }

        // Non-Session form used here to emphasize statefulness
        @OnWebSocketMessage
        public void message(Reader message) {
            if (listener == null) {
                synchronized (this) {
                    if (listener == null) {
                        var msg = gson.fromJson(message, IncomingMessage.class);
                        long after = Long.parseLong(msg.after());

                        var listener = new PGNotificationListener() {
                            long lastSnowflake = after;

                            @Override
                            public synchronized void notification(int processId, String channelName, String payload) {
                                try (var con = ds.getConnection()) {
                                    con.setAutoCommit(true);
                                    try (var stmt = con
                                            .prepareStatement("SELECT id, username, msg FROM messages WHERE id > ?")) {
                                        stmt.setLong(1, lastSnowflake);
                                        var rs = stmt.executeQuery();
                                        while (rs.next()) {
                                            lastSnowflake = rs.getLong("id");
                                            var username = rs.getString("username");
                                            var msg = rs.getString("msg");

                                            var ctx = new Context(Locale.US, Map.of(
                                                "liveUpdate", true,
                                                "messages", List.of(Map.of(
                                                    "id", lastSnowflake,
                                                    "username", username,
                                                    "msg", msg
                                                ))
                                            ));
                                            var fragment = ChatHandler.templateEngine.process("chat", Set.of("#messages"), ctx);
                                            session.sendText(fragment, org.eclipse.jetty.websocket.api.Callback.NOOP);
                                        }
                                    }
                                } catch (SQLException ex) {
                                    throw new RuntimeException(ex);
                                }
                            }

                            @Override
                            public synchronized void closed() {
                                session.close();
                            }
                        };
                        this.listener = listener;
                        listenCon.addNotificationListener("messages", listener);
                        listener.notification(0, null, null);
                    }
                }
            }
        }

        @OnWebSocketClose
        public void close(int statusCode, String reason) {
            if (listener != null) {
                listenCon.removeNotificationListener(listener);
            }
        }
    }

    private void doGet(Request req, Response resp) throws SQLException, IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        long lastSnowflake = -1;

        try (var con = ds.getConnection()) {
            con.setAutoCommit(true);
            try (var stmt = con.createStatement()) {
                var rs = stmt.executeQuery("SELECT id, username, msg FROM messages");
                while (rs.next()) {
                    lastSnowflake = rs.getLong("id");
                    messages.add(Map.of(
                            "id", lastSnowflake,
                            "username", rs.getString("username"),
                            "msg", rs.getString("msg")));
                }
            }
        }

        var ctx = new Context(Locale.US, Map.of(
                "messages", messages,
                "lastSnowflake", lastSnowflake));

        resp.setStatus(HttpStatus.OK_200);
        var headers = resp.getHeaders();
        headers.add(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8");
        headers.add(HttpHeader.PRAGMA, "no-cache");
        headers.add(HttpHeader.CACHE_CONTROL, "no-cache");
        headers.add(HttpHeader.EXPIRES, 0);

        templateEngine.process("chat", ctx, new OutputStreamWriter(Response.asBufferedOutputStream(req, resp)));
    }

    protected void doPost(Request req, Response resp) throws Exception {
        var timestamp = Instant.now();

        try (var con = ds.getConnection()) {
            con.setAutoCommit(false);
            var snowflake = counter.incrementAndGet(timestamp);
            try (var stmt = con.prepareStatement("INSERT INTO messages VALUES (?, ?, ?)")) {
                stmt.setLong(1, snowflake.rep());
                stmt.setString(2, "user");
                stmt.setString(3, Request.getParameters(req).getValue("msg"));
                stmt.execute();
            }
            try (var stmt = con.createStatement()) {
                stmt.execute("SELECT pg_notify('messages', '')");
            }
            con.commit();
        }
        resp.setStatus(HttpStatus.NO_CONTENT_204);
    }

    @Override
    public boolean handle(Request req, Response resp, Callback cb) throws Exception {
        if (!"/".equals(Request.getPathInContext(req)))
            return false;

        switch (req.getMethod()) {
            case "GET":
                doGet(req, resp);
                break;
            case "POST":
                doPost(req, resp);
                break;
            default:
                return false;
        }
        cb.succeeded();
        return true;
    }
}
