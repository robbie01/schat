package net.sohio.chat;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
// import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.gson.Gson;
import com.impossibl.postgres.api.jdbc.PGConnection;
import com.impossibl.postgres.api.jdbc.PGNotificationListener;

public class ChatHandler extends Handler.Abstract {
    private DataSource ds;
    private AtomicSnowflake counter;

    private static UriTemplatePathSpec nakedSpec = new UriTemplatePathSpec("/{channel}");
    private static UriTemplatePathSpec baseSpec = new UriTemplatePathSpec("/{channel}/");
    private static UriTemplatePathSpec pathSpec = new UriTemplatePathSpec("/{channel}/{endpoint}");
    private static ITemplateEngine templateEngine = buildTemplateEngine();

    private ChatHandler(DataSource ds, AtomicSnowflake counter) {
        this.ds = ds;
        this.counter = counter;
    }

    public static Handler from(Server server, DataSource ds, PGConnection listenerCon, AtomicSnowflake counter) {

        var ws = WebSocketUpgradeHandler.from(server, container -> {
            container.addMapping(pathSpec, (req, res, cb) -> {
                var params = pathSpec.getPathParams(Request.getPathInContext(req));
                if (!"ws".equals(params.get("endpoint"))) return null;

                return new WebSocketHandler(params.get("channel"), ds, listenerCon);
            });
        });
        ws.setHandler(new ChatHandler(ds, counter));

        var session = new SessionHandler();
        session.setHandler(ws);

        return session;
    }

    private static ITemplateEngine buildTemplateEngine() {
        var resolver = new ClassLoaderTemplateResolver();

        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setPrefix("/net/sohio/chat/templates/");
        resolver.setSuffix(".html");
        resolver.setCacheable(false);

        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @WebSocket
    public static class WebSocketHandler implements PGNotificationListener {
        private static record IncomingMessage(String after) {}

        private static int FRESH = 0;
        private static int LISTENING = 1;
        private static int CLOSED = 2;

        private static Gson gson = new Gson();

        private String channel; 
        private DataSource ds;
        private PGConnection listenerCon;
        private Session session;
        private int listening = FRESH;
        private long lastSnowflake = -1;

        public WebSocketHandler(String channel, DataSource ds, PGConnection listenerCon) throws SQLException {
            this.channel = channel;
            this.ds = ds;
            this.listenerCon = listenerCon;
        }

        @OnWebSocketOpen
        public synchronized void open(Session session) {
            this.session = session;
            session.setIdleTimeout(Duration.ZERO);
        }

        @OnWebSocketMessage
        public synchronized void message(Reader message) {
            if (listening != FRESH) return;
            listening = LISTENING;

            var msg = gson.fromJson(message, IncomingMessage.class);
            long after = Long.parseLong(msg.after());

            lastSnowflake = after;

            listenerCon.addNotificationListener("messages", this);
            this.notification(0, null, channel);
        }

        @OnWebSocketClose
        public synchronized void close(int statusCode, String reason) {
            if (listening == LISTENING) {
                listenerCon.removeNotificationListener(this);
            }
            listening = CLOSED;
        }

        // @OnWebSocketError
        // public void error(Throwable error) {
        //     System.err.println(error);
        // }

        @Override
        public synchronized void notification(int processId, String channelName, String payload) {
            if (!channel.equals(payload)) return;

            try (var con = ds.getConnection()) {
                con.setAutoCommit(true);
                try (var stmt = con
                        .prepareStatement("SELECT id, username, msg FROM messages WHERE id > ? AND channelId = (SELECT id FROM channels where name = ?)")) {
                    stmt.setLong(1, lastSnowflake);
                    stmt.setString(2, channel);
                    var rs = stmt.executeQuery();
                    var messages = new ArrayList<Map<String, Object>>();

                    while (rs.next()) {
                        lastSnowflake = rs.getLong("id");
                        var username = rs.getString("username");
                        var msg = rs.getString("msg");

                        messages.add(Map.of(
                            "id", lastSnowflake,
                            "username", username,
                            "msg", msg));
                    }

                    if (messages.size() > 0) {
                        var ctx = new Context(Locale.US, Map.of(
                            "liveUpdate", true,
                            "messages", messages));
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
    }

    private void doGet(String channel, Request req, Response resp) throws SQLException, IOException {
        var sesh = req.getSession(true);
        String username;
        if ((username = (String)sesh.getAttribute("username")) == null) {
            username = "myUsername";
            sesh.setAttribute("username", username);
        }

        var channels = new ArrayList<Map<String, Object>>();
        var messages = new ArrayList<Map<String, Object>>();
        long lastSnowflake = -1;

        try (var con = ds.getConnection()) {
            con.setAutoCommit(true);
            try (var stmt = con.prepareStatement("SELECT name FROM channels")) {
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    var name = rs.getString("name");
                    var channelInfo = new HashMap<String, Object>();
                    channelInfo.put("name", name);
                    if (!name.equals(channel))
                        channelInfo.put("href", Request.newHttpURIFrom(req, String.format("/%s/", name)).getPath());
                    channels.add(channelInfo);
                }
            }
            try (var stmt = con.prepareStatement("SELECT id, username, msg FROM messages WHERE channelId = (SELECT id FROM channels where name = ?)")) {
                stmt.setString(1, channel);
                var rs = stmt.executeQuery();
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
            "ws", req.getHttpURI().getPath() + "ws",
            "channels", channels,
            "messages", messages,
            "lastSnowflake", lastSnowflake));

        resp.setStatus(HttpStatus.OK_200);
        var headers = resp.getHeaders();
        headers.add(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8");
        headers.add(HttpHeader.CACHE_CONTROL, "no-cache");
        headers.add(HttpHeader.EXPIRES, 0);

        Set<String> selectors = null;
        if ("wrapper".equals(req.getHeaders().get("HX-Target")))
            selectors = Set.of("#wrapper");

        templateEngine.process("chat", selectors, ctx, new OutputStreamWriter(Response.asBufferedOutputStream(req, resp), StandardCharsets.UTF_8));
    }

    protected void doPost(String channel, Request req, Response resp) throws Exception {
        var sesh = req.getSession(false);
        var username = (String)sesh.getAttribute("username");
        var msg = Request.getParameters(req).getValue("msg").trim();

        if (!msg.isEmpty()) {
            var timestamp = Instant.now();

            try (var con = ds.getConnection()) {
                con.setAutoCommit(false);
                var snowflake = counter.incrementAndGet(timestamp);
                try (var stmt = con.prepareStatement("INSERT INTO messages VALUES (?, (SELECT id FROM channels where name = ?), ?, ?)")) {
                    stmt.setLong(1, snowflake.rep());
                    stmt.setString(2, channel);
                    stmt.setString(3, username);
                    stmt.setString(4, Request.getParameters(req).getValue("msg"));
                    stmt.execute();
                }
                try (var stmt = con.prepareStatement("SELECT pg_notify('messages', ?)")) {
                    stmt.setString(1, channel);
                    stmt.execute();
                }
                con.commit();
            }
        }

        resp.setStatus(HttpStatus.NO_CONTENT_204);
    }

    @Override
    public boolean handle(Request req, Response resp, Callback cb) throws Exception {
        var path = Request.getPathInContext(req);

        if (path.length() == 0 ||"/".equals(path)) {
            resp.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
            resp.getHeaders().put(
                HttpHeader.LOCATION,
                Request.newHttpURIFrom(req, "/general/").getPath()
            );
        } else if (nakedSpec.matches(path)) {
            resp.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
            resp.getHeaders().put(
                HttpHeader.LOCATION,
                Request.newHttpURIFrom(req, String.format("/%s/", nakedSpec.getPathParams(path).get("channel"))).getPath()
            );
        } else if (baseSpec.matches(path)) {
            var channel = baseSpec.getPathParams(path).get("channel");

            switch (req.getMethod()) {
                case "GET":
                    doGet(channel, req, resp);
                    break;
                case "POST":
                    doPost(channel, req, resp);
                    break;
                default:
                    return false;
            }
        } else {
            return false;
        }
        cb.succeeded();
        return true;
    }
}
