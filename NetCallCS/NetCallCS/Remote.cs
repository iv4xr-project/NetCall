using System;
using System.Linq;
using System.Collections.Generic;

using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;

using System.Threading;
using System.Threading.Tasks;

using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

using Ninja.WebSockets;
using Microsoft.Extensions.Logging;

namespace NetCallCS
{
    public class Remote
    {
        /// <summary>
        /// Incoming request to call a method here.
        /// </summary>
        public class RemoteRequestIncoming
        {
            [JsonInclude]
            public string obj;

            [JsonInclude]
            public string method;

            [JsonInclude]
            public JsonElement[] args;
        }

        /// <summary>
        /// Outgoing request to call a method remotely.
        /// </summary>
        public class RemoteRequestOutgoing
        {
            [JsonInclude]
            public string obj;

            [JsonInclude]
            public string method;

            [JsonInclude]
            public object[] args;
        }

        /// <summary>
        /// Incoming result from a remote call.
        /// </summary>
        /// <typeparam name="T"></typeparam>
        public class RemoteResultIncomming<T> where T : struct
        {
            [JsonInclude]
            public string error;

            [JsonInclude]
            public T? result;
        }

        /// <summary>
        /// Outgoing result from a call here.
        /// </summary>
        public class RemoteResultOutgoing
        {
            [JsonInclude]
            public string error;

            [JsonInclude]
            public object result;

            public static RemoteResultOutgoing FromErrorString(string err)
            {
                return new RemoteResultOutgoing()
                {
                    error = err,
                };
            }
            public static RemoteResultOutgoing FromResultObject(object obj)
            {
                return new RemoteResultOutgoing()
                {
                    result = obj,
                };
            }
        }

        public class RemoteObject
        {
            public string Id;
            public Dictionary<string, RemoteMethod> Methods;
        }
        public class RemoteMethod
        {
            public string Name;
            public int Args;
        }

        private readonly Dictionary<string, object> registeredObjects = new Dictionary<string, object>();

        private WebSocket client;

        public ILogger Log = Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance;

        public async Task Listen(int port)
        {
            var fac = new WebSocketServerFactory();

            Log.LogInformation($"Started listening for network calls on port {port}");
            var listener = new TcpListener(IPAddress.Loopback, port);
            listener.Start();

            while (true)
            {
                var token = new CancellationToken();
                var tcp = listener.AcceptTcpClient();
                Log.LogInformation("Client connected.");

                try
                {
                    var stream = tcp.GetStream();
                    var context = await fac.ReadHttpHeaderFromStreamAsync(stream);
                    var server = await fac.AcceptWebSocketAsync(context);
                    if (context.IsWebSocketRequest)
                    {
                        HandleIncoming(server, token);
                    }
                }
                catch (Exception e)
                {
                    Log.LogWarning(e, "Could not accept tcp stream.");
                    break;
                }
            }
        }

        private async void HandleIncoming(WebSocket socket, CancellationToken token)
        {
            ArraySegment<byte> buffer = new ArraySegment<byte>(new byte[1024]);

            while (true)
            {
                WebSocketReceiveResult socketResult;
                try
                {
                    socketResult = socket.ReceiveAsync(buffer, token).GetAwaiter().GetResult();
                }
                catch (System.IO.IOException e)
                {
                    Log.LogWarning(e, "Socket closed unexpectedly.");
                    return;
                }

                if (socketResult.MessageType == WebSocketMessageType.Close)
                {
                    return;
                }

                if (socketResult.Count > 1024)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.MessageTooBig, "Message frame exceeded 1024 bytes.", token);
                    return;
                }

                // Deserialize request
                string requestJson = Encoding.UTF8.GetString(buffer.Array, 0, socketResult.Count);
                Log.LogTrace($"RECEIVED REQUEST (RAW)");
                Log.LogTrace($"  Data: {requestJson}");

                var request = JsonSerializer.Deserialize<RemoteRequestIncoming>(requestJson);
                Log.LogDebug("RECEIVED REQUEST");
                Log.LogDebug($"  Object ID: {request.obj}");
                Log.LogDebug($"  Method   : {request.method}");
                Log.LogDebug($"  Args     : [{string.Join(", ", request.args.Select(x => x.ToString()))}]");

                // Call method
                RemoteResultOutgoing result = CallMethod(request);
                Log.LogDebug("SENDING RESULT");
                Log.LogDebug($"  Error : {result.error}");
                Log.LogDebug($"  Result: {result.result}");

                // Serialize result
                var resultJson = JsonSerializer.SerializeToUtf8Bytes(result);
                Log.LogTrace("SENDING RESULT (RAW)");
                Log.LogTrace($"  Data: {Encoding.Default.GetString(resultJson)}");

                await socket.SendAsync(new ArraySegment<byte>(resultJson), WebSocketMessageType.Text, true, CancellationToken.None);
            }
        }

        private RemoteResultOutgoing CallMethod(RemoteRequestIncoming request)
        {
            if (!registeredObjects.ContainsKey(request.obj))
                return RemoteResultOutgoing.FromErrorString("Requested object is not registered.");

            var instance = registeredObjects[request.obj];

            var method = instance.GetType().GetMethod(request.method);

            if (method == null)
                return RemoteResultOutgoing.FromErrorString("Requested method of object does not exist.");

            var mParams = method.GetParameters();

            var args = new object[request.args.Length];

            for (int i = 0; i < request.args.Length; i++)
            {
                var pType = mParams[i].ParameterType;
                var arg = request.args[i];

                args[i] = JsonSerializer.Deserialize(arg.GetRawText(), pType);
            }

            try
            {
                var methodResult = instance.GetType().GetMethod(request.method).Invoke(instance, args);
                return RemoteResultOutgoing.FromResultObject(methodResult);
            }
            catch (Exception e)
            {
                Log.LogError(e.ToString());
                return RemoteResultOutgoing.FromErrorString("The called method resulted in an exception.");
            }
        }

        public async Task Connect(string ip)
        {
            if (client != null)
                throw new Exception("Already connected to remote.");

            var fac = new WebSocketClientFactory();
            client = await fac.ConnectAsync(new Uri(ip));
        }

        public void Register(object obj, string id)
        {
            registeredObjects.Add(id, obj);
            Log.LogDebug($"Registered {obj.GetType().Name}#{obj.GetHashCode()} as {id}");
        }

        public void Unregister(object o)
        {
            foreach (var key in registeredObjects.Where(kvp => kvp.Value == o).Select(kvp => kvp.Key))
            {
                registeredObjects.Remove(key);
            }
        }

        public async Task<T?> RemoteCall<T>(string id, string method, params object[] args) where T : struct
        {
            var request = new RemoteRequestOutgoing
            {
                obj = id,
                method = method,
                args = args,
            };

            Log.LogDebug("SENDING REQUEST");
            Log.LogDebug($"  Object ID: {request.obj}");
            Log.LogDebug($"  Method   : {request.method}");
            Log.LogDebug($"  Args     : [{string.Join(", ", request.args.Select(x => x.ToString()))}]");

            byte[] requestJson = JsonSerializer.SerializeToUtf8Bytes(request);

            Log.LogTrace("SENDING REQUEST (RAW)");
            Log.LogTrace($"  Data: {Encoding.Default.GetString(requestJson)}");

            await client.SendAsync(new ArraySegment<byte>(requestJson), WebSocketMessageType.Text, true, CancellationToken.None);

            // Receiving the result
            var socketBuffer = new ArraySegment<byte>(new byte[1024]);
            var socketResult = await client.ReceiveAsync(socketBuffer, CancellationToken.None);


            string resultJson = Encoding.UTF8.GetString(socketBuffer.Array, 0, socketResult.Count);
            Log.LogTrace("RECEIVING RESULT (RAW)");
            Log.LogTrace($"  Data: {resultJson}");

            var result = JsonSerializer.Deserialize<RemoteResultIncomming<T>>(resultJson);

            Log.LogDebug("RECEIVING RESULT");
            Log.LogDebug($"  Error : {result.error}");
            Log.LogDebug($"  Result: {result.result}");

            if (result.error.Length > 0) {
                throw new Exception(result.error);
            }

            return result.result;
        }
    }
}
