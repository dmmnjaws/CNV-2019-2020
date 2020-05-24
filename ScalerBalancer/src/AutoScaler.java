/* Inpired in EC2LaunchWaitTerminate provided by the course */
package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;

import java.util.HashMap;
import java.util.Date;
import java.util.ConcurrentModificationException;

public class AutoScaler {

    private static AmazonEC2 ec2;
    static AmazonCloudWatch cloudWatch;
    private RunInstancesResult runInstancesResult;

    private List<String> instancesIdList;
    private HashMap<String, Double> instanceIdCPUMap = new HashMap<String, Double>();
    private HashMap<String, List<RequestData>> instanceIdRequestsMap = new HashMap<String, List<RequestData>>();

    public AutoScaler(){
        try {
            init();
            launchNewInstances(ec2, 3, 3);

        } catch (Exception e){
            System.out.println("Fail to initialize the Auto-Scaler");
        }
    }

    private static void init() throws Exception {

        /*
         * Translation: gets the aws credentions in the folder ~/.aws/credentials
         * and creates a ec2 client from the specified zone (we want us-east-1)
         */
        AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",
                    e);
        }

        ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion("us-east-1").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
    }

    public void execute(){
        try {
            while(true){
                try {
                    Thread.sleep(330000); //the CPU loads reported differ every 5 minutes and a half, therefore it only makes sence the auto-balance algorithm runs with this periodicity
                } catch (InterruptedException e){
                    System.out.println("Auto-Scaler thread interrupted...");
                    break;
                }
                //shows CPU utilization per instance
                instanceIdCPUMap = getInstancesCPU();

            }
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        } catch (Exception e){
            e.printStackTrace();
            System.out.println("\nThe Auto-Scaler failed.");
        }
    }

    public Set<Instance> getInstancesRunning(AmazonEC2 ec2){
        //Getting all the instances reservations
        DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesRequest.getReservations();
        //creating a list of instances
        Set<Instance> instances = new HashSet<Instance>();

        //getting all instances form the reservations
        for (Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }

        return instances;
    }


    public void launchNewInstances(AmazonEC2 ec2, int minNumberOfInstances, int maxNumberOfInstances){

        //preparing the instance request
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

        /* TODO: configure to use your AMI, key and security group */
        //configuring setting for new instance on runInstancesRequest
        runInstancesRequest.withImageId("ami-0c18f283bdb8449f2")
                .withInstanceType("t2.micro")
                .withMinCount(minNumberOfInstances)
                .withMaxCount(maxNumberOfInstances)
                .withKeyName("CNV-AWS")
                .withSecurityGroups("CNV-group");
        //launching the new instance with the previous settings
        RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);

        getInstancesIds(runInstancesResult);
    }


    public void getInstancesIds(RunInstancesResult runInstancesResult){

        List<String> instancesIds = new ArrayList<>();
        HashMap<String, List<RequestData>> instanceIdRequestsMap = new HashMap<String, List<RequestData>>();

        //getting instances from reservations
        for (Instance instance : runInstancesResult.getReservation().getInstances()) {

            //getting ID from every new instance
            String newInstanceId = instance.getInstanceId();
            System.out.println("AUTO-SCALER: Instance " + newInstanceId + " started.");
            instancesIds.add(newInstanceId);
            instanceIdRequestsMap.put(newInstanceId, new ArrayList<RequestData>());
        }

        this.instanceIdRequestsMap = instanceIdRequestsMap;
        this.instancesIdList = instancesIds;
    }

    public void terminateInstancesNow(List<String> instancesIds){

        TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();

        for (String id : instancesIds) {

            System.out.println("AUTO-SCALER: Instance " + id + " shutdown.");
            //terminating all instances
            this.instancesIdList.remove(id);
            this.instanceIdCPUMap.remove(id);
            this.instanceIdRequestsMap.remove(id);
            termInstanceReq.withInstanceIds(id);
            ec2.terminateInstances(termInstanceReq);

        }

    }

    public List<String> getInstanceList(){
        return this.instancesIdList;
    }

    public HashMap<String, List<RequestData>> getInstanceIdRequestsMap() {
        return this.instanceIdRequestsMap;
    }

    public synchronized HashMap<String, Double> getInstancesCPU(){

        long offsetInMilliseconds = 1000 * 60 * 10;
        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");
        List<Dimension> dims = new ArrayList<Dimension>();
        dims.add(instanceDimension);

        //gets all instances that are running
        Set<Instance> instances = getInstancesRunning(ec2);

        for (Instance instance : instances) {

            String name = instance.getInstanceId();
            String state = instance.getState().getName();

            if (state.equals("running") && !name.equals("i-00486fb98895a1a63")) {

                //System.out.println("running instance id = " + name);

                instanceDimension.setValue(name);
                GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                        .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
                        .withNamespace("AWS/EC2")
                        .withPeriod(60)
                        .withMetricName("CPUUtilization")
                        .withStatistics("Average")
                        .withDimensions(instanceDimension)
                        .withEndTime(new Date());
                GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request);
                List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();
                Double maxCPUUsage = Double.valueOf(0);

                for (Datapoint dp : datapoints) {
                    if (dp.getAverage() > maxCPUUsage){
                        maxCPUUsage = dp.getAverage();
                    }
                }

                System.out.println("\nAUTO-SCALER: CPU utilization for instance " + name + " = " + maxCPUUsage);
                instanceIdCPUMap.put(name, maxCPUUsage);

                if(this.instancesIdList.size() > 1) {
                    //TODO WE AREN'T ABLE TO SEND HTTP REQUESTS YET, BUT WHEN WE DO, WE SHOULD TEST WHAT KIND OF CPU LOAD A MACHINE INCURS IN.
                    if (maxCPUUsage.compareTo(Double.valueOf(3)) < 0) {
                        System.out.println("AUTO-SCALER: CPU usage of instance " + name + " is low!");
                        if (this.instanceIdRequestsMap.get(name).size() == 0) {
                            System.out.println("AUTO-SCALER: Instance " + name + " has no pending requests.");
                            ArrayList<String> thisInstance = new ArrayList<>();
                            thisInstance.add(name);
                            terminateInstancesNow(thisInstance);
                        }
                    }
                }

                //TODO ONCE AGAIN, WE AREN'T ABLE TO SEND HTTP REQUESTS YET, BUT WHEN WE DO, WE SHOULD TEST WHAT KIND OF CPU LOAD A MACHINE INCURS IN.
                if (maxCPUUsage.compareTo(Double.valueOf(75)) > 0) {
                    System.out.println("AUTO-SCALER: Instance " + name + " is overloaded!");
                    launchNewInstances(ec2, 1, 1);
                }

            }
            else {
                //System.out.println("instance id = " + name);
            }

            //System.out.println("Instance State : " + state +".");
        }
        return instanceIdCPUMap;
    }

    public String getInstanceDNSURL(String instanceId){
        DescribeInstancesResult describeInstancesResult = ec2.describeInstances();
        List<Reservation> reserves = describeInstancesResult.getReservations();
        Set<Instance> instances = new HashSet<Instance>();

        for(Reservation reservation : reserves){
            for(Instance instance : reservation.getInstances()){
                if(instance.getInstanceId().equals(instanceId)){
                    return instance.getPublicDnsName();
                }
            }
        }

        return null;
    }

    public synchronized void shutdown(){
        try{
            terminateInstancesNow(new ArrayList<>(this.instancesIdList));
        } catch (ConcurrentModificationException e){
            e.printStackTrace();
        }
    }

    public void appendRequest(String instanceId, RequestData request){
        if(this.instanceIdRequestsMap.get(instanceId) != null){
            System.out.println("Instance " + instanceId + " has a new request in queue.");
            this.instanceIdRequestsMap.get(instanceId).add(request);
        };
    }

    public void unappendRequest(String instanceId, RequestData request){
        System.out.println("Instance " + instanceId + " completed a request.");
        this.instanceIdRequestsMap.get(instanceId).remove(request);
    }

}

