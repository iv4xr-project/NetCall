package eu.iv4xr.netcallj;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Example
{
    Logger Log = Logger.getLogger("");

    public class SharedObject {
        int number = 0;

        public void Print(String text) {
            System.out.println(text);
        }

        public int AddAndPrint(int n) {
            number += n;
            System.out.println(number);
            return number;
        }

        public int AddTwo(AddNumbers v) {
            int result = v.A + v.B;
            System.out.format("%s + %s = %s\n", v.A, v.B, result);
            return result;
        }
    }

    public static class AddNumbers {
        public int A;
        public int B;

        public AddNumbers() { }
        public AddNumbers(int a, int b) {
            A = a; B = b;
        }
    }

    public static void main(String[] args) {
        new Example().Run();
    }

    public void Run() {
        // Setup logging
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tl:%1$tM:%1$tS %4$-6s | %5$s%6$s%n");
        Level level = Level.FINER;
        //Level level = Level.INFO;

        Log.setLevel(level);
        Log.getHandlers()[0].setLevel(level);
        Remote.Log.setLevel(level);

        // Actual example
        Log.info("NetcallJSExample");
        Remote r = new Remote();

        if (true) {
            RemoteExample(r);
        } else {
            ServerExample(r);
        }
    }

    public void ServerExample(Remote r) {
        Thread server = new Thread() {
            public void run() {
                r.Listen("localhost", 5556);
            }
        };
        server.start();

        SharedObject fizz = new SharedObject();
        SharedObject fazz = new SharedObject();

        r.Register(fizz, "fizz");
        r.Register(fazz, "fazz");

        try {
            server.join();
        } catch (Exception e) { }
    }

    public void RemoteExample(Remote r) {
        r.Connect("ws://localhost:5555");
        
        Log.info("Calling foo.Print(\"Hello!\")");
        r.RemoteCall("foo", "Print", new Object[]{ "Hello!" });

        Log.info("Calling bar.Print(\"World!\")");
        r.RemoteCall("bar", "Print", new Object[]{ "World!" });

        Log.info("Calling bar.AddAndPrint(4)");
        int baap4 = r.RemoteCall("bar", "AddAndPrint", new Object[]{ 4 });
        Log.info(String.format("  bar.AddAndPrint(4) = %s", baap4));
        
        Log.info("Calling bar.AddAndPrint(8)");
        int baap8 = r.RemoteCall("bar", "AddAndPrint", new Object[]{ 8 });
        Log.info(String.format("  bar.AddAndPrint(8) = %s", baap8));
        
        // Example: Sending and receiving data
        AddNumbers addition = new AddNumbers(10, 5);
        Log.info(String.format("%s + %s = ? (calling remote)", addition.A, addition.B));
        int addResult = r.RemoteCall("foo", "AddTwo", new Object[]{ addition });
        Log.info(String.format("  %s + %s = %s", addition.A, addition.B, addResult));

        r.finalize();
    }
}
