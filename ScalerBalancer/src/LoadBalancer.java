package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;

import java.util.concurrent.ExecutorService;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.HttpURLConnection;
import java.lang.Double;

public class LoadBalancer{

    private static AutoScaler autoScaler;
    private static boolean shutdown = false;

    public static void main (final String[] args) throws Exception {

        final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

        server.createContext("/ping", new MyPingHandler());
        server.createContext("/sudoku", new HandleRequest());

        // be aware! infinite pool of threads!
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(server.getAddress().toString());

        // Start autoscaler
        autoScaler = new AutoScaler();
        //health check
        checkInstances();

        Runnable autoScalerRoutine = new Runnable() {
            public void run() {
                autoScaler.execute();
            }
        };

        Thread checker = new Thread(autoScalerRoutine);
        checker.start();

        System.console().readLine();
        shutdown = true;
        System.out.println("\nShutting down the instances...");
        autoScaler.shutdown();
        checker.interrupt();
        checker.join();
        System.out.println("Shutting down the HttpServer...");
        server.stop(1);
        System.out.println("Shutting down the ThreadPool...");
        pool.shutdownNow();
    }

    public static void checkInstances(){

        List<String> instanceIds = autoScaler.getInstanceList();
        for (String instanceId : instanceIds) {
            try {

                String instanceDNS = "";
                while (instanceDNS.equals("")){
                    Thread.sleep(250);
                    instanceDNS = autoScaler.getInstanceDNSURL(instanceId);
                    System.out.println("DNS: " + instanceDNS);
                }
                URL url = new URL("http://" + instanceDNS + ":8000/ping");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");

                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(con.getInputStream()))) {

                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    System.out.println(instanceId + ": " + response.toString());

                }

            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("REQUEST: Failed HTTP request, looking for another server.");
                autoScaler.signalInstance(instanceId);
                //TODO when the server is down.
            }
        }
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

            System.out.println("\nREQUEST: HTTP Request received.");
            RequestData request = makeRequestId(t);

            //Load Balancing
            String solution = null;
            while(solution == null){
                String bestInstance = loadBalancing();
                if(bestInstance.equals("")){
                    System.out.println("\nWARNING: Request Failed");

                    if(!shutdown){
                        autoScaler.launchNewInstance();
                    }

                    break;
                }
                solution = getSolution(request, bestInstance, t);
            }

            final Headers hdrs = t.getResponseHeaders();

            hdrs.add("Content-Type", "application/json");

            hdrs.add("Access-Control-Allow-Origin", "*");

            hdrs.add("Access-Control-Allow-Credentials", "true");
            hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
            hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

            t.sendResponseHeaders(200, solution.length());
            OutputStream outputStream = t.getResponseBody();
            OutputStreamWriter osw = new OutputStreamWriter(outputStream, "UTF-8");
            osw.write(solution.toString());
            osw.flush();
            osw.close();
            outputStream.close();

            System.out.println("REQUEST: Query with params " + request.getQuery() + " answered.");
        }
    }

    public static String getSolution(RequestData request, String instanceId, HttpExchange t){

        String instanceDNS = autoScaler.getInstanceDNSURL(instanceId);

        try {

            autoScaler.appendRequest(instanceId, request);

            String urlParameters = request.getQuery();
            byte[] postData = urlParameters.getBytes("UTF-8");
            int postDataLength = postData.length;
            URL url = new URL("http://" + instanceDNS + ":8000/sudoku?" + urlParameters);

            System.out.println("\nREQUEST: Query received: " + url);

            HttpURLConnection con = (HttpURLConnection)url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; utf-8");
            con.setRequestProperty("Accept", "application/json");
            con.setDoOutput(true);

            try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
                wr.write(parseRequestBody(t.getRequestBody()).getBytes("UTF-8"));
            }

            StringBuilder response = null;
            try(BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"))) {
                response = new StringBuilder();
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }

            autoScaler.unappendRequest(instanceId, request);
            return response.toString();

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("REQUEST: Failed HTTP request, looking for another server.");
            autoScaler.signalInstance(instanceId);
            return null;
            //TODO when the server is down.
        }
    }

    public static RequestData makeRequestId(HttpExchange t) {

        // Get the query.
        final String query = t.getRequestURI().getQuery();

        final String strategy = query.substring(query.indexOf("s=") + 2, query.indexOf("&un="));
        final String n1 = query.substring(query.indexOf("n1=") + 3, query.indexOf("&n2="));
        final String n2 = query.substring(query.indexOf("n2=") + 3, query.indexOf("&i="));
        final String un = query.substring(query.indexOf("un=") + 3, query.indexOf("&n1="));

        return new RequestData(query, n1, n2, un, strategy);

    }

    public static String loadBalancing(){

        List<String> instanceIds = autoScaler.getInstanceList();
        String bestInstance = "";
        double lowestLoad = Double.MAX_VALUE;
        for (String instanceId : instanceIds){
            if(!autoScaler.getSignaledInstancesIdList().contains(instanceId)){
                if(lowestLoad > autoScaler.getInstanceLoad(instanceId)){
                    lowestLoad = autoScaler.getInstanceLoad(instanceId);
                    bestInstance = instanceId;
                }
            }
        }

        if(!bestInstance.equals("")){
            System.out.println("LOAD-BALANCER: instance " + bestInstance + " was selected to attend the request.");
        }

        return bestInstance;
    }

    public static String parseRequestBody(InputStream is) throws IOException {
        InputStreamReader isr =  new InputStreamReader(is,"utf-8");
        BufferedReader br = new BufferedReader(isr);

        // From now on, the right way of moving from bytes to utf-8 characters:
        int b;
        StringBuilder buf = new StringBuilder(512);
        while ((b = br.read()) != -1) {
            buf.append((char) b);
        }

        br.close();
        isr.close();

        return buf.toString();
    }
}
