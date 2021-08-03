package com.amazonaws.webtier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.codec.binary.Base64;

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.CreateTagsResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;

public class AppController extends Thread {

	static BasicAWSCredentials AWS_CREDENTIALS = new BasicAWSCredentials("",
			"");

	// instance varibales
	static String amiID;
	static String instanceType;
	static String securityGroup;

	// class variables
	static String requestUrl;
	static String responseUrl;
	static String inputBucketName;
	static String outputBucketName;

	public static void main(String[] args) {
		Options options = new Options();

		Option amiIDOption = new Option("amiID", true, "ami id");
		amiIDOption.setRequired(true);
		options.addOption(amiIDOption);

		Option instanceTypeOption = new Option("instanceType", true, "instance type");
		instanceTypeOption.setRequired(true);
		options.addOption(instanceTypeOption);

		Option securityGroupOption = new Option("securityGroup", true, "security group");
		securityGroupOption.setRequired(true);
		options.addOption(securityGroupOption);

		Option requestUrlOption = new Option("requestUrl", true, "request url");
		requestUrlOption.setRequired(true);
		options.addOption(requestUrlOption);

		Option responseUrlOption = new Option("responseUrl", true, "response url");
		responseUrlOption.setRequired(true);
		options.addOption(responseUrlOption);

		Option inputBucketNameOption = new Option("inputBucketName", true, "input bucket name");
		inputBucketNameOption.setRequired(true);
		options.addOption(inputBucketNameOption);

		Option outputBucketNameOption = new Option("outputBucketName", true, "output bucket name");
		outputBucketNameOption.setRequired(true);
		options.addOption(outputBucketNameOption);

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			formatter.printHelp("Option names", options);

			System.exit(1);
		}

		amiID = cmd.getOptionValue("amiID");
		instanceType = cmd.getOptionValue("instanceType");
		securityGroup = cmd.getOptionValue("securityGroup");
		requestUrl = cmd.getOptionValue("requestUrl");
		responseUrl = cmd.getOptionValue("responseUrl");
		inputBucketName = cmd.getOptionValue("inputBucketName");
		outputBucketName = cmd.getOptionValue("outputBucketName");
		// setting the variables

		// checking the sqs queue for autoscaling
		while (true) {

			System.out.println("App Controller running ... ");
			int noOfMessagesInQueue = getApproxMessageCount();
			int currentInstancesCount = 0;

			if (noOfMessagesInQueue > 0) {

				currentInstancesCount = getCurrentRunningInstancesCount();
				
				if (currentInstancesCount + noOfMessagesInQueue <= 20) {
					for (int i = 0; i < noOfMessagesInQueue; i++) {
						createInstance();
						sleep(2000);
					}
				} else {
					for (int i = 0; i < 20 - currentInstancesCount; i++) {
						createInstance();
						sleep(2000);
					}
				}
				
				// current thread goes to sleep so that app logic instances would process and
				// delete the request from sqs
				int count = 0;
				while (count < 3) {
					sleep(15000);
					count++;
				}

			} else {
				sleep(3000);
			}
		}
	}

	public static void sleep(int millSeconds) {

		try {
			Thread.currentThread();
			Thread.sleep(millSeconds);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return;
	}

	// finding the mode of approx messages taken 3 times
	public static int getApproxMessageCount() {
		int maximumVal = 0, maximumCount = 0, i, j;
		int[] a = new int[3];
		for (i = 0; i < a.length; i++) {
			a[i] = getMessageCount();
		}
		for (i = 0; i < a.length; ++i) {
			int count = 0;
			for (j = 0; j < a.length; ++j) {
				if (a[j] == a[i])
					++count;
			}

			if (count > maximumCount) {
				maximumCount = count;
				maximumVal = a[i];
			}
		}
		return maximumVal;

	}

	public static int getMessageCount() {
		final AmazonSQS sqs = AmazonSQSClientBuilder.standard().withRegion("us-east-1")
				.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();

		GetQueueAttributesRequest sqsRequest = new GetQueueAttributesRequest(requestUrl);
		sqsRequest.setAttributeNames(Arrays.asList("ApproximateNumberOfMessages"));

		GetQueueAttributesResult result = sqs.getQueueAttributes(sqsRequest);
		String approxMessageCount = result.getAttributes().get("ApproximateNumberOfMessages");

		int queueMessageLength = Integer.parseInt(approxMessageCount);
		return queueMessageLength;

	}

	public static int getCurrentRunningInstancesCount() {
		int count = 0;
		try {
			final AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1")
					.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();

			// Create a DescribeInstancesRequest
			DescribeInstancesRequest request = new DescribeInstancesRequest();

			// Find the running instances
			DescribeInstancesResult response = ec2.describeInstances(request);

			for (Reservation reservation : response.getReservations()) {

				for (Instance instance : reservation.getInstances()) {
					if (instance.getState().getName().equalsIgnoreCase("running")
							|| instance.getState().getName().equalsIgnoreCase("pending"))
						count++;
				}
			}

		} catch (SdkClientException e) {
			e.getStackTrace();
		}
		return count;
	}

	public static void createInstance() {
		final AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1")
				.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();

		RunInstancesRequest runRequest = new RunInstancesRequest().withImageId(amiID).withInstanceType(instanceType)
				.withUserData(getDataScript()).withSecurityGroupIds(securityGroup).withKeyName("")
				.withMaxCount(1).withMinCount(1);

		RunInstancesResult response = ec2.runInstances(runRequest);
		// String instanceId = response.instances().get(0).instanceId();
		try{
		String reservation_id = response.getReservation().getInstances().get(0).getInstanceId();

		int currentCount = getCurrentRunningInstancesCount();
		Tag tag = new Tag().withKey("Name").withValue("app-tier " + currentCount);
		CreateTagsRequest tag_request = new CreateTagsRequest().withResources(reservation_id).withTags(tag);
		// CreateTagsResult tag_response = ec2.createTags(tag_request);
		ec2.createTags(tag_request);
		System.out.printf("Successfully started EC2 Instance %s based on AMI %s", reservation_id, amiID);
		System.out.println();
		} catch (AmazonEC2Exception e) {			
			System.out.println("Illegal instance id! cannot attach a tag");
			
		}

		return;
	}

	public static String getDataScript() {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("#! /bin/bash");
		lines.add("cd /home/ubuntu/classifier/");
		lines.add("java -jar apptier-1.0.0-jar-with-dependencies.jar -inputBucketName " + inputBucketName
				+ " -outputBucketName " + outputBucketName + " -requestUrl " + requestUrl + " -responseUrl "
				+ responseUrl);

		String str = new String(Base64.encodeBase64(joinWithDelimiter(lines, "\n").getBytes()));
		return str;
	}

	public static String joinWithDelimiter(Collection<String> s, String delimiter) {
		StringBuilder builder = new StringBuilder();
		Iterator<String> iter = s.iterator();
		while (iter.hasNext()) {
			builder.append(iter.next());
			if (!iter.hasNext()) {
				break;
			}
			builder.append(delimiter);
		}
		return builder.toString();
	}

}
