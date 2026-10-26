import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


class ObjectStorage:
    def __init__(self, settings):
        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            region_name="us-east-1",
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
                connect_timeout=5,
                read_timeout=15,
                retries={"max_attempts": 2},
            ),
        )
        try:
            self.client.head_bucket(Bucket=self.bucket)
        except ClientError as exc:
            if str(exc.response["Error"]["Code"]) not in ("404", "NoSuchBucket"):
                raise
            self.client.create_bucket(Bucket=self.bucket)

    def put(self, key, data):
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data)

    def get(self, key):
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        try:
            return response["Body"].read()
        finally:
            response["Body"].close()

    def delete(self, key):
        self.client.delete_object(Bucket=self.bucket, Key=key)

    def health(self):
        self.client.head_bucket(Bucket=self.bucket)
