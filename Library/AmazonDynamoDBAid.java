package pt.ulisboa.tecnico.cnv.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.lang.InterruptedException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;

public class AmazonDynamoDBAid{

    private static AmazonDynamoDB amazonDynamoDB;

    public static void init() throws Exception {

    	ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();

    	try {

    		credentialsProvider.getCredentials();
    	
    	} catch (Exception e) {

    		throw new AmazonClientException(
    			"Cannot load the credentials from the credential profiles file. " +
                "Please make sure that your credentials file is at the correct " +
                "location (~/.aws/credentials), and is in valid format.",
                e);
    	
    	}

    	amazonDynamoDB = AmazonDynamoDBClientBuilder.standard().withCredentials(credentialsProvider).withRegion("us-east-1").build();
    
    	createTable("metrics");
    }

    public static void createTable(String tableName){

    	try{

    		CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(tableName)
            	.withKeySchema(new KeySchemaElement().withAttributeName("requestParameters").withKeyType(KeyType.HASH))
            	.withAttributeDefinitions(new AttributeDefinition().withAttributeName("requestParameters").withAttributeType(ScalarAttributeType.S))
            	.withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L)); 
    

        	TableUtils.createTableIfNotExists(amazonDynamoDB, createTableRequest);
        	TableUtils.waitUntilActive(amazonDynamoDB, tableName);

        	DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(tableName);
        	TableDescription tableDescription = amazonDynamoDB.describeTable(describeTableRequest).getTable();
        	//System.out.println("Table Description: " + tableDescription);

        } catch (AmazonServiceException ase) {
        
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        
        } catch (AmazonClientException ace) {
        
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());

    	} catch (InterruptedException ie){
			System.out.println("Interrupt exception");
		}

}

    public static void addItem(Map<String, AttributeValue> newItem){
    
    	PutItemRequest putItemRequest = new PutItemRequest("metrics", newItem);
    	PutItemResult putItemResult = amazonDynamoDB.putItem(putItemRequest);
    	System.out.println("Result: " + putItemRequest);
    }

    public static List<Map<String, AttributeValue>> getItem(String requestParameters){
    
    	HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();

    	Condition condition = new Condition().withComparisonOperator(ComparisonOperator.EQ.toString())
    		.withAttributeValueList(new AttributeValue(requestParameters));

    	scanFilter.put("requestParameters", condition);
    	ScanRequest scanRequest = new ScanRequest("metrics").withScanFilter(scanFilter);
    	ScanResult scanResult = amazonDynamoDB.scan(scanRequest);

    	return scanResult.getItems();
    }

    public static Map<String, AttributeValue> createItem(String requestParameters, long instructions, long basicBlocks, long allocs){
    
    	Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
    	item.put("requestParameters", new AttributeValue(requestParameters));
    	item.put("instructions", new AttributeValue().withN(Long.toString(instructions)));
    	item.put("basicBlocks",  new AttributeValue().withN(Long.toString(basicBlocks)));
		item.put("allocs",  new AttributeValue().withN(Long.toString(allocs)));
    
    	return item;
    }
}