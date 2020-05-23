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

public class AutoScaler {

    private static AmazonEC2 ec2;
    private List<String> instancesIdList;
    private boolean shutdown;

    public AutoScaler(){
        try {
            init();
            this.instancesIdList = launchNewInstances(ec2, 1, 1);
        } catch (Exception e){
            System.out.println("Fail to initialize AutoScaler");
        }

        this.shutdown = false;
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
    }

    public void execute(){
        try {
            while(true){
                Thread.sleep(2000);
                //Check if instances are all OK
                //Check if instances CPU utilization and launch or terminate some
                if(shutdown){
                    terminateInstancesNow(this.instancesIdList);
                    break;
                }
            }
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        } catch (Exception e){
            System.out.println("Falha a iniciar autoScaler");
        }
    }
    /*
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
     */

    public List<String> launchNewInstances(AmazonEC2 ec2, int minNumberOfInstances, int maxNumberOfInstances){

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

            return getInstancesIds(runInstancesResult);
    }

    public List<String> getInstancesIds(RunInstancesResult runInstancesResult){
 
        List<String> instancesIds = new ArrayList<>();

        //getting instances from reservations
        for (Instance instance : runInstancesResult.getReservation().getInstances()) {

            //getting ID from every new instance
            String newInstanceId = instance.getInstanceId();
            instancesIds.add(newInstanceId);
        }

        return instancesIds;
    }

    public void terminateInstancesNow(List<String> instancesIds){

        TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();

        for (String id : instancesIds) {

        //terminating all instances
        System.out.println("Terminating the instance.");
        termInstanceReq.withInstanceIds(id);
        ec2.terminateInstances(termInstanceReq);

        }
       
    }

    public List<String> getInstanceList(){
        return this.instancesIdList;
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
        this.shutdown = true;
    }

}