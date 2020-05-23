package pt.ulisboa.tecnico.cnv.server;

import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import pt.ulisboa.tecnico.cnv.server.AmazonDynamoDBAid;
import pt.ulisboa.tecnico.cnv.solver.Solver;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;
import pt.ulisboa.tecnico.cnv.solver.SolverFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class WebServer {

    private static Map<Long,ArrayList<AtomicLong>> metrics = new HashMap<>();
    
    public static Map<Long,ArrayList<AtomicLong>> getMap(){
        return metrics;
    }

	public static void main(final String[] args) throws Exception {

		//final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8000), 0);

		final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

		server.createContext("/ping", new MyPingHandler());
		server.createContext("/sudoku", new MyHandler());

		// be aware! infinite pool of threads!
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();

		System.out.println(server.getAddress().toString());
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

	static class MyPingHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			String response = "OK";
			t.sendResponseHeaders(200, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	static class MyHandler implements HttpHandler {
		@Override
		public void handle(final HttpExchange t) throws IOException {

			ArrayList<AtomicLong> data = new ArrayList<AtomicLong>();
			data.add(new AtomicLong(0));
			data.add(new AtomicLong(0));

			metrics.put(Thread.currentThread().getId(), data);

			// Get the query.
			final String query = t.getRequestURI().getQuery();
			//System.out.println("> Query:\t" + query);

			// Break it down into String[].
			final String[] params = query.split("&");

			// Store as if it was a direct call to SolverMain.
			final ArrayList<String> newArgs = new ArrayList<>();
			for (final String p : params) {
				final String[] splitParam = p.split("=");
				newArgs.add("-" + splitParam[0]);
				newArgs.add(splitParam[1]);
			}
			newArgs.add("-b");
			newArgs.add(parseRequestBody(t.getRequestBody()));
            
			newArgs.add("-d");

			// Store from ArrayList into regular String[].
			final String[] args = new String[newArgs.size()];
			int i = 0;
			for(String arg: newArgs) {
				args[i] = arg;
				i++;
			}	

			// Get user-provided flags.
			final SolverArgumentParser ap = new SolverArgumentParser(args);

			// Create solver instance from factory.
			final Solver s = SolverFactory.getInstance().makeSolver(ap);

			//Solve sudoku puzzle
			JSONArray solution = s.solveSudoku();

			// Send response to browser.
			final Headers hdrs = t.getResponseHeaders();

            //t.sendResponseHeaders(200, responseFile.length());
            
			///hdrs.add("Content-Type", "image/png");
            hdrs.add("Content-Type", "application/json");

			hdrs.add("Access-Control-Allow-Origin", "*");

            hdrs.add("Access-Control-Allow-Credentials", "true");
			hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
			hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

            t.sendResponseHeaders(200, solution.toString().length());

            final OutputStream os = t.getResponseBody();
            OutputStreamWriter osw = new OutputStreamWriter(os, "UTF-8");
            osw.write(solution.toString());
            osw.flush();
            osw.close();

			os.close();
				
			System.out.println("> Sent response to " + t.getRemoteAddress().toString());
			ArrayList<AtomicLong> newData = metrics.get(Thread.currentThread().getId());
			try{

				AmazonDynamoDBAid.init();
				AmazonDynamoDBAid.addItem(AmazonDynamoDBAid.createItem(ap.getN1() + "x" + ap.getN2() + " " + ap.getUn() + " " + ap.getSolverStrategy() , newData.get(0).get(), newData.get(1).get()));

			}catch (IOException e){
				e.printStackTrace();
			}catch (Exception e){
				e.printStackTrace();
			}

		}
	}

	public static void writeToFile(SolverArgumentParser ap, ArrayList<AtomicLong> newData){
    	try {
			FileWriter writer = new FileWriter("MetricsFile.txt", true);

			writer.write("Tamanho da Sudoku: " + ap.getN1() + "x" + ap.getN2() + "\n");
			writer.write("Posicoes por preencher: " + ap.getUn() + "\n");
			writer.write("Estrategia Usada:" + ap.getSolverStrategy() + "\n");
			writer.write("Instrucoes: " + newData.get(0).get() + " blocos: " + newData.get(1).get() + "\n\n");
			writer.close();
		} catch (IOException e){
    		System.out.println("Erro na escrita do ficheiro");
		}
	}

}
