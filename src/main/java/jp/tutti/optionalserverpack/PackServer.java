package jp.tutti.optionalserverpack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Tiny embedded HTTP server that serves the pack zip files placed in the
 * plugin's packs/ folder, so no external hosting is needed.
 * Only files directly inside that folder are served.
 */
final class PackServer {

    private final Logger logger;
    private final File packsDir;
    private final int port;
    private HttpServer server;

    PackServer(Logger logger, File packsDir, int port) {
        this.logger = logger;
        this.packsDir = packsDir;
        this.port = port;
    }

    void start() throws IOException {
        if (!packsDir.isDirectory() && !packsDir.mkdirs()) {
            throw new IOException("packs フォルダを作成できません: " + packsDir.getPath());
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "OptionalServerPack-PackServer");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        logger.info("パック配信用Webサーバーを起動しました (port " + port + ", dir " + packsDir.getPath() + ")");
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            // Only plain file names directly under packs/ are served.
            File f = new File(packsDir, name);
            if (name.isEmpty() || !f.getParentFile().equals(packsDir) || !f.isFile()) {
                send(ex, 404, "text/plain", "not found".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            byte[] body = Files.readAllBytes(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        } catch (Exception e) {
            logger.warning("パック配信中にエラー: " + e.getMessage());
            try {
                send(ex, 500, "text/plain", "error".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // response already started; nothing more to do
            }
        } finally {
            ex.close();
        }
    }

    private void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
