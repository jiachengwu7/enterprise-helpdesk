import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


class ObjectStorage:
    # 用途：创建 S3 兼容客户端，检查并按需创建桶。
    # 输入：settings：端点、凭据及桶名。
    # 输出：无返回值；访问对象存储，必要时创建桶。
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

    # 用途：保存文档原件。
    # 输入：key：对象键；data：文件字节。
    # 输出：无返回值；写入对象存储。
    def put(self, key, data):
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data)

    # 用途：读取原件并确保关闭响应流。
    # 输入：key：对象键。
    # 输出：完整文件字节；存储异常向上传播。
    def get(self, key):
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        try:
            return response["Body"].read()
        finally:
            response["Body"].close()

    # 用途：删除已废弃的原件。
    # 输入：key：对象键。
    # 输出：无返回值；删除对象存储中的对象。
    def delete(self, key):
        self.client.delete_object(Bucket=self.bucket, Key=key)

    # 用途：检查存储桶可访问性。
    # 输入：无显式参数，使用实例客户端与桶名。
    # 输出：成功无返回值；不可访问时抛出存储异常。
    def health(self):
        self.client.head_bucket(Bucket=self.bucket)
