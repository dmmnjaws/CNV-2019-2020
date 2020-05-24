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

    public static void main (final String[] args) throws Exception {

        final HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);

        server.createContext("/ping", new MyPingHandler());
        server.createContext("/sudoku", new HandleRequest());

        // be aware! infinite pool of threads!
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(server.getAddress().toString());

        // Start autoscaler
        autoScaler = new AutoScaler();

        Runnable autoScalerRoutine = new Runnable() {
            public void run() {
                autoScaler.execute();
            }
        };

        Thread checker = new Thread(autoScalerRoutine);
        checker.start();

        System.console().readLine();
        System.out.println("Shutting down the instances...");
        autoScaler.shutdown();
        checker.interrupt();
        checker.join();
        System.out.println("Shutting down the HttpServer...");
        server.stop(1);
        System.out.println("Shutting down the ThreadPool...");
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

            //TODO: Load Balancing

            byte[] solution = getSolution(request, autoScaler.getInstanceList().get(0));

            t.sendResponseHeaders(200, solution.length);
            OutputStream outputStream = t.getResponseBody();
            outputStream.write(solution);
            outputStream.close();
            System.out.println("Query responded: " + request.getQuery());
        }
    }

    public static byte[] getSolution(RequestData request, String instanceId){

        String instanceDNS = autoScaler.getInstanceDNSURL(instanceId);

        try {

            autoScaler.appendRequest(instanceId, request);

            String urlParameters = request.getQuery();
            byte[] postData = urlParameters.getBytes("UTF-8");
            int postDataLength = postData.length;
            URL url = new URL("http://" + instanceDNS + ":8000/sudoku");

            System.out.println("\nQuery: " + url + "?" + urlParameters);

            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("User-Agent", "Java Client");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try( DataOutputStream wr = new DataOutputStream( con.getOutputStream())) {
                wr.write( postData );
            }

            StringBuilder content;

            try(BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {

                String line;
                content = new StringBuilder();

                while((line = br.readLine()) != null){
                    content.append(line);
                    content.append(System.lineSeparator());
                }

            }

            autoScaler.unappendRequest(instanceId, request);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed http request, looking for another server.");

            //TODO when the server is down.
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
