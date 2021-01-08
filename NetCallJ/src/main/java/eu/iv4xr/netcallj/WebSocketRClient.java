package eu.iv4xr.netcallj;

import java.lang.InterruptedException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.net.URI;
import java.net.URISyntaxException;

// Websocket client
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

class WebSocketRClient extends WebSocketClient {
    private CompletableFuture<String> futureResult;
    private boolean verbose = false;

    public WebSocketRClient(URI uri) {
        super(uri);
    }

    public String receive() {
        futureResult = new CompletableFuture<String>();
        try {
            return futureResult.get();
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    @Override
    public void onMessage(String msg) {
        if (verbose) {
            System.out.println("[WS] Received message:");
            System.out.println(msg);
        }
        futureResult.complete(msg);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        if (verbose) {
            System.out.println("[WS] Connection opened.");
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (verbose) {
            System.out.format("[WS] Connection closed: %s\n", reason);
        }
    }

    @Override
    public void onError(Exception ex) {
        if (verbose) {
            System.out.format("[WS] Exception: %s\n", ex);
        }
    }
};