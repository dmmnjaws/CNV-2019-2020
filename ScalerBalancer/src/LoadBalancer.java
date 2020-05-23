package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;

import java.util.concurrent.ExecutorService;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.HttpURLConnection;


public class LoadBalancer{

    private static AutoScaler autoScaler;
    private static List<String> instancesList;

    public static void main (final String[] args) throws Exception {

        final HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);

        server.createContext("/ping", new MyPingHandler());
        server.createContext("/sudoku", new HandleRequest());

        // Start autoscaler
        autoScaler = new AutoScaler();
        instancesList = autoScaler.getInstanceList();
        Runnable autoScalerRoutine = new Runnable() {
            public void run() {
                autoScaler.execute();
            }
        };
        Thread checker = new Thread(autoScalerRoutine);
        checker.start();

        // be aware! infinite pool of threads!
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(server.getAddress().toString());

        System.console().readLine();
        autoScaler.shutdown();
        checker.join();
        server.stop(1);
        pool.shutdownNow();
    }

    static class MyPingHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange t) throws IOException {
            String response = "OK";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class HandleRequest implements HttpHandler {

        @Override
        public void handle(final HttpExchange t) throws IOException {

            RequestData request = makeRequestId(t);
            byte[] solution = getSolution(request, autoScaler.getInstanceDNSURL(instancesList.get(0)));

            t.sendResponseHeaders(200, solution.length);
            OutputStream outputStream = t.getResponseBody();
            outputStream.write(solution);
            outputStream.close();
            System.out.println("Query responded: " + request.getQuery());
        }
    }

    public static byte[] getSolution(RequestData request, String instanceDNS){

        try {
            URL url = new URL("http://" + instanceDNS + ":8000/sudoku?" + request.getQuery());

            System.out.println("\nURL: " + url);

            HttpURLConnection httpRequest = (HttpURLConnection) url.openConnection();
            httpRequest.setRequestMethod("GET");

            System.out.println("HttpLength: " + httpRequest.getContentLength());

            if (httpRequest.getContentLength() == -1) {
                return null;

            } else {
                byte[] solution = new byte[httpRequest.getContentLength()];
                DataInputStream dataInputStream = new DataInputStream(httpRequest.getInputStream());

                int responseBytes = 0;

                while (responseBytes < httpRequest.getContentLength()) {
                    responseBytes += dataInputStream.read(solution, responseBytes, solution.length - responseBytes);
                }

                if (httpRequest != null) {
                    dataInputStream.close();
                    httpRequest.disconnect();
                }

                return solution;
            }

        } catch (Exception e){
            System.out.println("Failed http request");
        }

        return null;
    }

    public static RequestData makeRequestId(HttpExchange t) {

        // Get the query.
        final String query = t.getRequestURI().getQuery();

        System.out.println("Query received: " + query);

        final String strategy = query.substring(query.indexOf("s=") + 2, query.indexOf("&un="));
        final String n1 = query.substring(query.indexOf("n1=") + 3, query.indexOf("&n2="));
        final String n2 = query.substring(query.indexOf("n2=") + 3, query.indexOf("&i="));
        final String un = query.substring(query.indexOf("un=") + 3, query.indexOf("&n1="));

        return new RequestData(query, n1, n2, un, strategy);

    }

}
