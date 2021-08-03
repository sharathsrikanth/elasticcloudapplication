package com.amazonaws.apptier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;

public class AppLogic extends Thread {s

	// Credentials
	static BasicAWSCredentials AWS_CREDENTIALS = new BasicAWSCredentials("");
	// Elastic components varibales
	final AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1")
			.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();
	final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
			.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();
	final AmazonSQS sqs = AmazonSQSClientBuilder.standard().withRegion("us-east-1")
			.withCredentials(new AWSStaticCredentialsProvider(AWS_CREDENTIALS)).build();

	// class variables
	String requestUrl;
	String responseUrl;
	String inputBucketName;
	String outputBucketName;

	// Constructors
	public AppLogic(String requesturl, String responseurl, String inputbucketname, String outputbucketname) {
		this.requestUrl = requesturl;
		this.responseUrl = responseurl;
		this.inputBucketName = inputbucketname;
		this.outputBucketName = outputbucketname;

	}

	// default constructor
	public AppLogic() {

	}

	public static void main(String[] args) {

		// Getting arguements from App controller and parsing it
		Options options = new Options();

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
			formatter.printHelp("utility-name", options);

			System.exit(1);
		}

		String requesturl = cmd.getOptionValue("requestUrl");
		String responseurl = cmd.getOptionValue("responseUrl");
		String inputBucketName = cmd.getOptionValue("inputBucketName");
		String outputBucketName = cmd.getOptionValue("outputBucketName");

		AppLogic app = new AppLogic(requesturl, responseurl, inputBucketName, outputBucketName);

		// keep running until self terminates the instance
		while (true) {
			app.requestListener();
		}

	}

	// Listens to the request from the queue
	public void requestListener() {
		System.out.println("App Logic Listening ... ");
		HashMap<String, String> result = new HashMap<>();
		List<Message> messages = new ArrayList<>();
		ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(requestUrl).withMaxNumberOfMessages(1);
		messages = sqs.receiveMessage(receiveMessageRequest).getMessages();

		int count = 0;
		while (count < 4 && messages.size() == 0) {
			messages = sqs.receiveMessage(receiveMessageRequest).getMessages();
			try {
				Thread.currentThread();
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			count++;
		}

		if (messages.size() == 0) {
			String currentInstanceId = getInstanceId();
			killCurrentInstance(currentInstanceId);
		} else {
			String imagename;
			String receipthandle;
			String output;
			for (Message message : messages) {
				imagename = message.getBody();
				receipthandle = message.getReceiptHandle();

				output = runClassification(imagename);
				result.put(imagename, output);
				if (!result.isEmpty()) {
					// put the response in s3
					storeTheResponse(result);

					// put the response in the sqs
					storeTheResponseinSQS(result);

					// delete the request from sqs
					deleteTheRequest(receipthandle);
				} else {
					System.out.println("Output is null for the image: " + imagename);
				}
			}
		}

	}

	public void storeTheResponseinSQS(HashMap<String, String> result) {
		Map.Entry<String, String> entry = result.entrySet().iterator().next();
		String tempKey = entry.getKey();
		String[] key = tempKey.split("\\.");
		if (key.length == 2) {
			String value = entry.getValue();
			String output = "(" + key[0] + " , " + value + ")";
			SendMessageRequest send_msg_request = new SendMessageRequest().withQueueUrl(responseUrl)
					.withMessageBody(output).withDelaySeconds(0);
			sqs.sendMessage(send_msg_request);
		} else {
			System.out.println("Invalid image name! Cannot store in the SQS");
		}

	}

	public String getInstanceId() {
		String currentInstanceID = null;
		try {
			URL ec2MetaUrl = new URL("http://169.254.169.254/latest/meta-data/instance-id");
			URLConnection EC2MD = ec2MetaUrl.openConnection();
			BufferedReader bufferreader = new BufferedReader(new InputStreamReader(EC2MD.getInputStream()));
			currentInstanceID = bufferreader.readLine();
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("currentInstanceID :" + currentInstanceID);
		return currentInstanceID;
	}

	public void killCurrentInstance(String instanceId) {
		TerminateInstancesRequest requestToTerminate = new TerminateInstancesRequest().withInstanceIds(instanceId);
		ec2.terminateInstances(requestToTerminate);
	}

	public void storeTheResponse(HashMap<String, String> result) {
		Map.Entry<String, String> entry = result.entrySet().iterator().next();
		String tempKey = entry.getKey();
		String[] key = tempKey.split("\\.");
		if (key.length == 2) {
			String value = entry.getValue();
			String output = "(" + key[0] + " , " + value + ")";
			try {
				s3.putObject(outputBucketName, key[0], output);
				System.out.println("Output stored successfully: " + value);
			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				System.exit(1);
			}
		} else {
			System.out.println("Invalid image name! Cannot store in the S3");
		}
	}

	public void deleteTheRequest(String receipthandle) {
		sqs.deleteMessage(new DeleteMessageRequest().withQueueUrl(requestUrl).withReceiptHandle(receipthandle));
	}

	public String runClassification(String imagename) {

		String imageclassification = null;
		Process proc = null;
		// String dir = "/home/ubuntu/classifier/";

		// reading the image and storing it in the local file
		try {
			S3Object o = s3.getObject(inputBucketName, imagename);
			S3ObjectInputStream s3is = o.getObjectContent();
			FileOutputStream fos = new FileOutputStream(new File(imagename));
			byte[] read_buf = new byte[1024];
			int read_len = 0;
			while ((read_len = s3is.read(read_buf)) > 0) {
				fos.write(read_buf, 0, read_len);
			}
			s3is.close();
			fos.close();
		} catch (AmazonServiceException e) {
			System.err.println(e.getErrorMessage());
			System.exit(1);
		} catch (FileNotFoundException e) {
			System.err.println(e.getMessage());
			System.exit(1);
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.exit(1);
		}

		// ProcessBuilder processBuilderCopy = new ProcessBuilder("").inheritIO();
		ProcessBuilder processBuilderExecute = new ProcessBuilder("python3", "image_classification.py",
				"./" + imagename).inheritIO();

		try {

			proc = processBuilderExecute.start();
			proc.waitFor();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			System.out.println("Inside process builder error io exception");
			e1.printStackTrace();
		} catch (InterruptedException e2) {
			System.out.println("Inside process builder error interruption");
		}

		try {
			File myObj = new File("result.txt");
			Scanner myReader = new Scanner(myObj);
			while (myReader.hasNextLine()) {
				imageclassification = myReader.nextLine();

			}
			myReader.close();
		} catch (FileNotFoundException e) {
			System.out.println("An error occurred while fetching result.txt");
			e.printStackTrace();
		}

		// deleting the local file
		File imageFile = new File(imagename);

		if (imageFile.delete()) {
			System.out.println("Image file deleted successfully: " + imagename);
		}

		File resultFile = new File("result.txt");

		if (resultFile.delete()) {
			System.out.println("Result file deleted successfully ");
		}

		return imageclassification;

	}

}
