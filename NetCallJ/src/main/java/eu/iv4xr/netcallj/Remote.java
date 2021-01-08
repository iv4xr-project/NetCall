package eu.iv4xr.netcallj;

import java.net.URI;
import java.net.URISyntaxException;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

// Data structures
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.HashMap;

// Logging
import java.util.logging.Level;
import java.util.logging.Logger;

// Websocket client
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

// Websocket server
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

// JSON parsing
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Remote 
{
    public static Logger Log = Logger.getLogger(Remote.class.getTypeName());

    public static class RemoteRequest {
        public String obj;
        public String method;
        public Object[] args;

        public RemoteRequest() { }
        public RemoteRequest(String obj, String method, Object[] args) {
            this.obj = obj;
            this.method = method;
            this.args = args;
        }
    }

    public static class RemoteResult {
        public String error = "";
        public Object result = 0;

        public static RemoteResult FromErrorString(String err) {
            RemoteResult res = new RemoteResult();
            res.error = err;
            return res;
        }

        public static RemoteResult FromResultObject(Object obj) {
            RemoteResult res = new RemoteResult();
            res.result = obj;
            return res;
        }
    }

    private WebSocketRClient client;
    private WebSocketServer server;
    private boolean verbose = false;

    Map<String, Object> registeredObjects = new HashMap<String, Object>();

    /**
     * Listens to calls from a remote source.
     * @param hostname
     * @param port
     */
    public void Listen(String host, int port)
    {
        this.server = new WebSocketServer(new InetSocketAddress(host, port)) {
            public void onStart() {
                Log.info(String.format("Started listening on %s:%s", host, port));
            }

            public void onOpen(WebSocket socket, ClientHandshake hs) {

            }

            public void onMessage(WebSocket socket, String message) {
                Log.finer("RECEIVED REQUEST (RAW)");
                Log.finer(String.format("  data: %s", message));

                RemoteRequest r;
                ObjectMapper m = new ObjectMapper();
                try {
                    r = m.readValue(message, RemoteRequest.class);
                } catch (JsonProcessingException e) {
                    System.out.println(e);
                    return;
                }
            
                Log.fine("RECEIVED REQUEST");
                Log.fine(String.format("  Object ID: %s", r.obj));
                Log.fine(String.format("  Method   : %s", r.method));
                Log.fine(String.format("  Args     : %s", Arrays.toString(r.args)));

                RemoteResult result = CallMethod(r);
                try {
                    String json = m.writeValueAsString(result);
                    socket.send(json);
                } catch (JsonProcessingException e) {
                    Log.severe(String.format("%s", e));
                    return;
                }

            }

            public void onClose(WebSocket socket, int code, String reason, boolean remote) {
                Log.fine("Socket connection closed");
            }

            public void onError(WebSocket socket, Exception ex) {
                Log.severe(String.format("Socket error: %s", ex));
            }

            private RemoteResult CallMethod(RemoteRequest r) {
                if (!registeredObjects.containsKey(r.obj)) {
                    return RemoteResult.FromErrorString("Requested object is not registered.");
                }

                Object instance = registeredObjects.get(r.obj);

                Log.fine(String.format("Calling on instance %s:", instance.getClass().getCanonicalName()));

                Method[] methods = instance.getClass().getMethods();
                
                Optional<Method> method = Arrays.stream(methods)
                .filter(x -> x.getName().equals(r.method) && x.getParameterCount() == r.args.length)
                .findFirst();
                
                Method method_;
                try {
                    method_ = method.get();
                } catch (NoSuchElementException e) {
                    return RemoteResult.FromErrorString("Requested method of object does not exist.");
                }
                Log.fine(String.format("  Method %s.", method_.getName()));

                ObjectMapper m = new ObjectMapper();
                Log.fine("Parameters:");
                for (int i = 0; i < r.args.length; i++) {
                    //r.args[i] = method_.getParameterTypes()[i].cast(r.args[i]);
                    Class c = method_.getParameterTypes()[i];
                    Log.fine(c.getCanonicalName());
                    r.args[i] = m.convertValue(r.args[i], c);
                }

                Object methodResult;

                try {
                    methodResult = method_.invoke(instance, r.args);
                } catch (IllegalAccessException e) {
                    Log.severe(e.toString());
                    return RemoteResult.FromErrorString("The called method resulted in an exception.");
                } catch (InvocationTargetException e) {
                    Log.severe(e.toString());
                    Log.severe(e.getCause().toString());
                    return RemoteResult.FromErrorString("The called method resulted in an exception.");
                }

                return RemoteResult.FromResultObject(methodResult);
            }
        };

        this.server.run();
    }

    /**
     * Connects to a remote source.
     * @param ip
     */
    public boolean Connect(String ip)
    {
        try {
            this.client = new WebSocketRClient(new URI(ip));
        } catch(URISyntaxException ex) {;
            Log.severe("URI or IP malformed.");
            return false;
        }
        client.connect();
        Log.info(String.format("Connected to %s", ip));
        return true;
    }

    public void Register(Object obj, String id) {
        registeredObjects.put(id, obj);
        Log.fine(String.format("Registed object %s as id %s", obj.hashCode(), id));
    }

    public void Unregister(Object obj) {
        for (String key : (Iterable<String>)registeredObjects
                .entrySet().stream()
                .filter(entry -> obj == entry.getValue())
                .map(Map.Entry::getKey)) {
            registeredObjects.remove(key);
            Log.fine(String.format("Unregistered object with id %s", key));
        }
    }

    public <T> T RemoteCall(String id, String method, Object[] args) {
        ObjectMapper m = new ObjectMapper();
        RemoteRequest req = new RemoteRequest(id, method, args);
        String json;


        Log.fine("Sending request");
        Log.fine(String.format("  Id    : %s", req.obj));
        Log.fine(String.format("  Method: %s", req.method));
        Log.fine(String.format("  Args  : %s", req.args));

        try {
            json = m.writeValueAsString(req);
        } catch (JsonProcessingException e) { 
            return null; 
        }

        Log.finer("Sending request (raw)");
        Log.finer(String.format("  Data: %s", json));

        client.send(json);

        // Receiving result
        String resultJson = client.receive();

        Log.finer("Received result (raw)");
        Log.finer(String.format("  Data: %s", resultJson));

        RemoteResult result;
        try {
            result = m.readValue(resultJson, RemoteResult.class);
        } catch (JsonProcessingException e) {
            return null;
        }

        Log.fine("Received result");
        Log.fine(String.format("  error : %s", result.error));
        Log.fine(String.format("  result: %s", result.result));

        if (result.error != null) {
            Log.severe(result.error);
            return null;
        }

        return (T)result.result;
    }

    @Override
    protected void finalize() {
        client.close();
    }
}
