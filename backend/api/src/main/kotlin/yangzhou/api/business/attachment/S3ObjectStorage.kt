package yangzhou.api.business.attachment

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import yangzhou.api.config.AttachmentProperties
import java.net.URI
import java.time.Duration

/** S3 兼容驱动(RustFS/MinIO/AWS):path-style + 静态凭据;presign 10 分钟。 */
class S3ObjectStorage(
    props: AttachmentProperties,
) : ObjectStorage {
    private val bucket = props.bucket

    private val presigner =
        S3Presigner
            .builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    private val s3 =
        S3Client
            .builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClient(UrlConnectionHttpClient.create())
            .build()

    override fun presignPut(
        key: String,
        contentType: String,
    ): String =
        presigner
            .presignPutObject(
                PutObjectPresignRequest
                    .builder()
                    .signatureDuration(SIGN_TTL)
                    .putObjectRequest(
                        PutObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    ).build(),
            ).url()
            .toString()

    override fun presignGet(key: String): String =
        presigner
            .presignGetObject(
                GetObjectPresignRequest
                    .builder()
                    .signatureDuration(SIGN_TTL)
                    .getObjectRequest(
                        GetObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    ).build(),
            ).url()
            .toString()

    override fun delete(key: String) {
        s3.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }

    companion object {
        private val SIGN_TTL: Duration = Duration.ofMinutes(10)
    }
}
