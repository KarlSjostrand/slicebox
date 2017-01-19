/*
 * Copyright 2017 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.storage

import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import java.io.{ByteArrayInputStream, InputStream}

/**
  * S3 client for storage.
  *
  * @param bucket bucket name
  */
class S3Facade(val bucket: String) {

  // AWS credentials provider chain that looks for credentials in this order:
  // Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_KEY
  // Java System Properties - aws.accessKeyId and aws.secretKey
  // Instance profile credentials delivered through the Amazon EC2 metadata service
  val s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain(), new ClientConfiguration().withProtocol(Protocol.HTTPS))


  def delete(key: String): Unit = {
    s3.deleteObject(bucket, key)
  }

  def exists(key: String): Boolean = {
    s3.doesObjectExist(bucket,key)
  }

  def get(key: String): InputStream = {
    val s3Object = s3.getObject(bucket, key)
    s3Object.getObjectContent
  }

  def upload(key: String, content: Array[Byte]): String = {
    try {
      val metadata: ObjectMetadata = new ObjectMetadata
      metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)
      metadata.setContentLength(content.length)
      metadata.setContentType("application/octet-stream")
      val putObjectRequest: PutObjectRequest = new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata)
      s3.putObject(putObjectRequest)
    } catch {
      case e: Exception =>
        //log.error("Failed to upload to S3", e)
        throw e
    }
    key
  }

}
