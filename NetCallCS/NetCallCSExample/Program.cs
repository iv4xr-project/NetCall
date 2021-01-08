using System.Threading;
using Microsoft.Extensions.Logging;
using NetCallCS;

namespace NetCallCSExample
{
    class Program
    {
        public static ILogger Log;
        static void Main(string[] args)
        {
            using ILoggerFactory loggerFactory =
                LoggerFactory.Create(builder =>
                {
                    builder.SetMinimumLevel(LogLevel.Trace);
                    builder.AddSimpleConsole(options =>
                    {
                        options.IncludeScopes = true;
                        options.SingleLine = true;
                        options.TimestampFormat = "hh:mm:ss ";
                    });
                });

            Log = loggerFactory.CreateLogger<Program>();

            var remote = new Remote()
            {
                Log = loggerFactory.CreateLogger<Remote>(),
            };
#if false
            RemoteExample(remote);
#else
            ServerExample(remote);
#endif
        }

        static async void ServerExample(Remote remote)
        {
            // Set up server
            Thread t = new Thread(() => {
                var listenTask = remote.Listen(5555);

                listenTask.GetAwaiter().GetResult();
            });
            t.Start();

            // Create objects
            var foo = new SharedObject();
            var bar = new SharedObject();

            // Register objects
            remote.Register(foo, "foo");
            remote.Register(bar, "bar");


            // Keep current thread activite until server termination.
            t.Join();
        }

        static void RemoteExample(Remote remote)
        {
            remote.Connect("ws://localhost:5556").Wait();

            remote.RemoteCall<int>("fizz", "Print", "Hello from C#!").Wait();
            Log.LogInformation("4 + 3 = ? (Remote call)");
            int result = remote.RemoteCall<int>("fazz", "AddTwo", new AddNumbers()
            {
                A = 4,
                B = 3,
            }).GetAwaiter().GetResult().Value;
            Log.LogInformation($"  4 + 3 = {result}");
        }
    }

    class SharedObject {
        int number = 0;

        public void Print(string text)
        {
            Program.Log.LogInformation($"text: {text}");
        }

        public int AddAndPrint(int n)
        {
            number += n;
            Program.Log.LogInformation($"AddAndPrint: {number}");
            return number;
        }

        public int AddTwo(AddNumbers v)
        {
            var result = v.A + v.B;
            Program.Log.LogInformation($"Calculated: {v.A} + {v.B} = {result}");
            return result;
        }
    }

    struct AddNumbers
    {
        public int A { get; set; }

        public int B { get; set; }
    }
}
