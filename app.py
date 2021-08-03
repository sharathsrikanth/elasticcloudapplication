from flask import Flask, render_template, request
import boto3
from werkzeug.utils import secure_filename

app = Flask(__name__)

Input_Bucket = boto3.client('s3',
                  aws_access_key_id = "",
                  aws_secret_access_key = ""
                 )

Output_Bucket = boto3.resource('s3',
                  aws_access_key_id = "",
                  aws_secret_access_key = ""
                 )

request_queue = boto3.client('sqs',
                  region_name = "us-east-1",
                  aws_access_key_id = "",
                  aws_secret_access_key = ""
                 )

response_queue = boto3.client('sqs',
                  region_name = "us-east-1",
                  aws_access_key_id = "",
                  aws_secret_access_key = ""
                 )

Input_Bucket_Name = ""
Output_Bucket_Name = ""
Request_Queue_URL = ""
Response_Queue_URL = ""

@app.route('/')
def home():
    return render_template("index.html")


@app.route('/upload', methods=['POST'])
def upload():
    try:
        if request.method == 'POST':
            images = request.files.getlist('file[]')
            if images:
                for img in images:
                    filename = secure_filename(img.filename)
                    img.save(filename)

                    if filename.lower().endswith(('.png','.jpg','.jpeg')):             

                        Input_Bucket.upload_file(
                            Bucket = Input_Bucket_Name,
                            Filename = filename,
                            Key = filename
                        )

                        request_queue.send_message(
                            QueueUrl = Request_Queue_URL,
                            DelaySeconds = 0,
                            MessageBody = filename
                        )
                msg = "Upload done !"
                return render_template("index.html", msg = msg)

    except:
        msg = "File not selected !"
        return render_template("index.html", msg = msg)



@app.route('/sqsoutput')
def sqsoutput():

    output_list = []

    while True:
        # Receive message from SQS Queue
        output = response_queue.receive_message(
            QueueUrl = Response_Queue_URL,
            MessageAttributeNames = ['All'],
            MaxNumberOfMessages = 1
        )

        try:
            body = output['Messages'][0]['Body']
            output_list.append(body)

            # Delete each received message from the response queue"""
            response_queue.delete_message(
                QueueUrl = Response_Queue_URL,
                ReceiptHandle = output['Messages'][0]['ReceiptHandle']
            )
        except:
            msg1 = "No More results to display, Response Queue is empty !."
            #output_list.append(msg1)
            return render_template("index.html", result = output_list, msg1=msg1)


@app.route('/s3output')
def s3output():

    output_list = []
    result = Output_Bucket.Bucket(Output_Bucket_Name)

    for obj in result.objects.all():
        body = obj.get()['Body'].read().decode()
        output_list.append(body)

    msg1 = "No More results to display, Response Queue is empty !."
    return render_template("index.html", result = output_list, msg1=msg1)


if __name__=="__main__":
    app.run(host='0.0.0.0', debug=True)
