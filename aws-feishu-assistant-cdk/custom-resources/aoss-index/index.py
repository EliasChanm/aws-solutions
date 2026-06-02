import json
import os
import time
import urllib.request
import hashlib
import boto3
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

def handler(event, context):
    print(json.dumps(event))
    request_type = event["RequestType"]
    props = event["ResourceProperties"]
    collection_endpoint = props["CollectionEndpoint"]
    index_name = props["IndexName"]
    vector_dimension = int(props.get("VectorDimension", "1024"))

    try:
        if request_type == "Create":
            # Wait for collection to be fully active
            time.sleep(30)
            body = json.dumps({
                "settings": {"index.knn": True},
                "mappings": {
                    "properties": {
                        "bedrock-knowledge-base-default-vector": {
                            "type": "knn_vector",
                            "dimension": vector_dimension,
                            "method": {"engine": "faiss", "name": "hnsw", "parameters": {"ef_construction": 512, "m": 16}}
                        },
                        "AMAZON_BEDROCK_METADATA": {"type": "text", "index": False},
                        "AMAZON_BEDROCK_TEXT_CHUNK": {"type": "text", "index": True}
                    }
                }
            })
            _send_aoss_request("PUT", collection_endpoint, index_name, body)
            time.sleep(10)
        elif request_type == "Delete":
            try:
                _send_aoss_request("DELETE", collection_endpoint, index_name)
            except Exception as e:
                print(f"Delete index failed (may already be gone): {e}")

        _send_cfn_response(event, context, "SUCCESS", {"IndexName": index_name})
    except Exception as e:
        print(f"Error: {e}")
        _send_cfn_response(event, context, "FAILED", {}, str(e))

def _send_aoss_request(method, endpoint, index_name, body=None):
    url = f"{endpoint}/{index_name}"
    session = boto3.Session()
    creds = session.get_credentials().get_frozen_credentials()
    region = os.environ.get("AWS_REGION", "us-east-1")

    data = body.encode("utf-8") if body else None
    headers = {"Content-Type": "application/json"} if body else {}

    req = AWSRequest(method=method, url=url, data=data, headers=headers)
    if data:
        req.headers["X-Amz-Content-Sha256"] = hashlib.sha256(data).hexdigest()
    SigV4Auth(creds, "aoss", region).add_auth(req)

    http_req = urllib.request.Request(url, data=data, method=method)
    for k, v in dict(req.headers).items():
        http_req.add_header(k, v)

    resp = urllib.request.urlopen(http_req, timeout=30)
    result = resp.read().decode("utf-8")
    print(f"AOSS {method} {index_name}: {resp.status} {result[:200]}")
    return result

def _send_cfn_response(event, context, status, data, reason=None):
    body = json.dumps({
        "Status": status,
        "Reason": reason or f"See CloudWatch Log Stream: {context.log_stream_name}",
        "PhysicalResourceId": event.get("PhysicalResourceId", context.log_stream_name),
        "StackId": event["StackId"],
        "RequestId": event["RequestId"],
        "LogicalResourceId": event["LogicalResourceId"],
        "Data": data
    }).encode("utf-8")
    req = urllib.request.Request(event["ResponseURL"], data=body, method="PUT",
                                 headers={"Content-Type": "", "Content-Length": str(len(body))})
    urllib.request.urlopen(req)
