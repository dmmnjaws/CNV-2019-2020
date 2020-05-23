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
        this.predictedLoad = 0;
        calculatePredictedLoad();
    }

    public void calculatePredictedLoad(){

        try {
            AmazonDynamoDBAid.init();
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Map<String, AttributeValue>> metrics = AmazonDynamoDBAid.getItem(lines + "x" + columns + " " + unsignedPositions + " " + strategy);

        if(!metrics.isEmpty()){
            Map<String, AttributeValue> metric = metrics.get(0);
            System.out.println("\nMetrics: " + metric.get("instructions").getN() + " " + metric.get("basicBlocks").getN());
            this.predictedLoad = (double) 0.5 * (double) Double.valueOf(metric.get("instructions").getN()) + 0.5 * (double) Double.valueOf(metric.get("basicBlocks").getN());
        }
    }

    public String getQuery(){
        return this.query;
    }

}