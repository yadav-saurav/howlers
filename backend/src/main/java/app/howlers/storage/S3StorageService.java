package app.howlers.storage;

import app.howlers.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * S3-compatible driver ({@code howlers.storage.driver=s3}): AWS S3, MinIO, or anything speaking the S3 API (D-023).
 * <ul>
 *   <li><b>Private bucket.</b> Objects are written without ACLs and the bucket is expected to block public access. Every
 *       read goes through the API ({@code /files/**} for public media, ownership checks, share links or signed grants
 *       for {@code private/} keys), so a private file can never be fetched straight from the bucket.</li>
 *   <li><b>Streaming uploads.</b> Streams up to the threshold become one PutObject; larger ones become a multipart upload
 *       in 8 MB parts, so a 500 MB podcast never sits in memory. A failed multipart upload is aborted.</li>
 *   <li><b>Ranged reads.</b> {@link #resource} returns a Resource whose stream opens lazily: Spring's Range support skips to
 *       the requested offset before reading, and that skip becomes an S3 {@code Range: bytes=N-} request instead of
 *       downloading the prefix. Closing a partly read stream aborts the connection rather than draining it.</li>
 * </ul>
 */
public class S3StorageService implements StorageService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    static final int PART_SIZE = 8 * 1024 * 1024; // S3's minimum part size is 5 MB (except the last part)

    private final S3Client s3;
    private final String bucket;
    private final int threshold;

    public S3StorageService(S3Client s3, String bucket, long multipartThresholdBytes) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("howlers.storage.s3.bucket (S3_BUCKET) is required for the s3 driver");
        this.s3 = s3;
        this.bucket = bucket;
        this.threshold = (int) Math.max(PART_SIZE, Math.min(multipartThresholdBytes, 64L * 1024 * 1024));
    }

    /** Builds the client from configuration; MinIO needs {@code pathStyle=true} and an endpoint. */
    public static S3StorageService create(AppProperties.S3 cfg) {
        if (cfg == null) throw new IllegalStateException("howlers.storage.s3.* must be configured for the s3 driver");
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(cfg.region() == null || cfg.region().isBlank() ? "us-east-1" : cfg.region()))
                .forcePathStyle(cfg.pathStyle())
                .credentialsProvider(credentials(cfg))
                // Only send/validate checksums when the operation requires them: keeps older MinIO releases compatible.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(64));
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) b.endpointOverride(URI.create(cfg.endpoint()));
        S3StorageService svc = new S3StorageService(b.build(), cfg.bucket(), Math.max(1, cfg.multipartThresholdMb()) * 1024 * 1024);
        if (cfg.createBucket()) svc.ensureBucket();
        return svc;
    }

    private static AwsCredentialsProvider credentials(AppProperties.S3 cfg) {
        boolean hasStatic = cfg.accessKey() != null && !cfg.accessKey().isBlank() && cfg.secretKey() != null && !cfg.secretKey().isBlank();
        return hasStatic ? StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey()))
                : DefaultCredentialsProvider.create();
    }

    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created storage bucket {}", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created storage bucket {}", bucket);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        requireSafe(key);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public void put(String key, InputStream in, String contentType) {
        requireSafe(key);
        byte[] first;
        try {
            first = in.readNBytes(threshold);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (first.length < threshold) {
            put(key, first, contentType);
            return;
        }
        String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).build()).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            byte[] chunk = first;
            int partNumber = 1;
            // The first buffer may be larger than PART_SIZE (threshold); S3 only needs every part but the last >= 5 MB.
            while (chunk.length > 0) {
                String etag = s3.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(partNumber).contentLength((long) chunk.length).build(), RequestBody.fromBytes(chunk)).eTag();
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                partNumber++;
                chunk = in.readNBytes(PART_SIZE);
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()).build());
        } catch (IOException | RuntimeException e) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
            } catch (RuntimeException abortFailed) {
                log.warn("Could not abort multipart upload {} for {}: {}", uploadId, key, abortFailed.toString());
            }
            if (e instanceof IOException io) throw new UncheckedIOException(io);
            throw (RuntimeException) e;
        }
    }

    @Override
    public Optional<StoredObject> get(String key) {
        if (!StorageService.isSafeKey(key)) return Optional.empty();
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            GetObjectResponse r = in.response();
            return Optional.of(new StoredObject(in, r.contentLength() == null ? -1 : r.contentLength(), StorageService.contentTypeFor(key)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Resource> resource(String key) {
        return head(key).map(h -> new S3Resource(key, h.contentLength() == null ? 0 : h.contentLength(),
                h.lastModified() == null ? 0 : h.lastModified().toEpochMilli()));
    }

    @Override
    public boolean exists(String key) {
        return head(key).isPresent();
    }

    private Optional<HeadObjectResponse> head(String key) {
        if (!StorageService.isSafeKey(key)) return Optional.empty();
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        if (!StorageService.isSafeKey(key)) return;
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            log.warn("Could not delete {} from storage: {}", key, e.toString()); // best effort, like the local driver
        }
    }

    @Override
    public boolean healthy() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    @Override
    public void close() {
        s3.close();
    }

    private static void requireSafe(String key) {
        if (!StorageService.isSafeKey(key)) throw new IllegalArgumentException("Invalid storage key");
    }

    /** A seekable view of one object; every {@link #getInputStream()} is a fresh, lazily opened ranged GET. */
    final class S3Resource extends AbstractResource {
        private final String key;
        private final long length;
        private final long lastModified;

        S3Resource(String key, long length, long lastModified) {
            this.key = key;
            this.length = length;
            this.lastModified = lastModified;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public long lastModified() {
            return lastModified;
        }

        @Override
        public String getFilename() {
            return key.substring(key.lastIndexOf('/') + 1);
        }

        @Override
        public String getDescription() {
            return "S3 object [" + bucket + "/" + key + "]";
        }

        @Override
        public InputStream getInputStream() {
            return new RangedStream(key, length);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof S3Resource r && r.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /**
     * Opens the GET on first read, from the current position. {@code skip} before the first read only moves the
     * position, which turns Spring's "skip to the range start" into an HTTP Range request.
     */
    final class RangedStream extends InputStream {
        private final String key;
        private final long length;
        private long position;
        private ResponseInputStream<GetObjectResponse> delegate;
        private boolean eof;

        RangedStream(String key, long length) {
            this.key = key;
            this.length = length;
        }

        private InputStream open() {
            if (delegate == null) {
                if (position >= length) {
                    eof = true;
                    return InputStream.nullInputStream();
                }
                GetObjectRequest.Builder req = GetObjectRequest.builder().bucket(bucket).key(key);
                if (position > 0) req.range("bytes=" + position + "-");
                delegate = s3.getObject(req.build());
            }
            return delegate;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) return 0;
            if (delegate == null) {
                long s = Math.min(n, Math.max(0, length - position));
                position += s;
                return s;
            }
            long s = delegate.skip(n);
            position += s;
            return s;
        }

        @Override
        public int read() throws IOException {
            if (eof) return -1;
            int b = open().read();
            if (b < 0) eof = true;
            else position++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (eof) return -1;
            int n = open().read(buf, off, len);
            if (n < 0) eof = true;
            else position += n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return delegate == null ? 0 : delegate.available();
        }

        @Override
        public void close() throws IOException {
            if (delegate == null) return;
            // A range read usually stops before the end: abort instead of draining the rest of a large object.
            if (!eof) delegate.abort();
            delegate.close();
        }
    }

    /** For tests and diagnostics. */
    String bucket() {
        return bucket;
    }
}
