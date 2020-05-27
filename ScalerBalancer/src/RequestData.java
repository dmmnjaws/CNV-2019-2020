package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.sun.net.httpserver.HttpExchange;
import pt.ulisboa.tecnico.cnv.server.AmazonDynamoDBAid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RequestData{

    private String query;
    private String lines;
    private String columns;
    private String unsignedPositions;
    private String strategy;
    private double predictedLoad;

    public RequestData(String query, String lines, String columns, String unsignedPositions, String strategy){

        this.query = query;
        this.lines = lines;
        this.columns = columns;
        this.strategy = strategy;
        this.unsignedPositions = unsignedPositions;
        this.predictedLoad = 1;
        calculatePredictedLoad();
    }

    public void calculatePredictedLoad(){

        try {
            AmazonDynamoDBAid.init();
        } catch (Exception e) {
            e.printStackTrace();
        }

        String requestId = lines + "x" + columns + " " + unsignedPositions + " " + strategy;
        List<Map<String, AttributeValue>> metrics = AmazonDynamoDBAid.getItem(requestId);

        if(!metrics.isEmpty()){
            Map<String, AttributeValue> metric = metrics.get(0);
            System.out.println("\nLOAD-BALANCER: Metrics collected for request " + requestId + ": "
                    + "\n\tInstructions#: " + metric.get("instructions").getN()
                    + "\n\tBasic Blocks#: " + metric.get("basicBlocks").getN()
                    + "\n\tAllocs#: " + metric.get("allocs").getN() + "\n");
            this.predictedLoad = (double) 0.000001 * (double) Double.valueOf(metric.get("instructions").getN())
                    + 0.000001 * (double) Double.valueOf(metric.get("basicBlocks").getN())
                    + (double) Double.valueOf(metric.get("allocs").getN()) ;
        }
    }

    public String getQuery(){
        return this.query;
    }
    public double getPredictedLoad() { return this.predictedLoad; }

    public String getN1() { return this.lines; }
    public String getN2() { return this.columns; }
    public String getUn() { return this.unsignedPositions; }
    public String getS() { return this.strategy; }

}