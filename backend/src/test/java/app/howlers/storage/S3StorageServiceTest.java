package app.howlers.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * The S3 driver against an in-memory {@link S3Client} (no Docker, no network). The fake implements the handful of
 * operations the driver uses with S3's semantics (ranges, multipart parts, 404s) and records what it was asked to do.
 */
class S3StorageServiceTest {

    static final class FakeS3 implements S3Client {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, String> types = new ConcurrentHashMap<>();
        final Map<String, TreeMap<Integer, byte[]>> uploads = new ConcurrentHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> ranges = new ArrayList<>();
        final List<PutObjectRequest> puts = new ArrayList<>();
        int aborts;
        int aborted;
        boolean bucketExists = true;
        boolean failParts;

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {}

        @Override
        public PutObjectResponse putObject(PutObjectRequest r, RequestBody body) {
            calls.add("put");
            puts.add(r);
            objects.put(r.key(), read(body));
            types.put(r.key(), r.contentType());
            return PutObjectResponse.builder().eTag("e").build();
        }

        @Override
        public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest r) {
            calls.add("create-mpu");
            String id = "u" + uploads.size();
            uploads.put(id, new TreeMap<>());
            return CreateMultipartUploadResponse.builder().uploadId(id).build();
        }

        @Override
        public UploadPartResponse uploadPart(UploadPartRequest r, RequestBody body) {
            calls.add("part");
            if (failParts && r.partNumber() == 2) throw new IllegalStateException("network");
            uploads.get(r.uploadId()).put(r.partNumber(), read(body));
            return UploadPartResponse.builder().eTag("etag" + r.partNumber()).build();
        }

        @Override
        public CompleteMultipartUploadResponse completeMultipartUpload(CompleteMultipartUploadRequest r) {
            calls.add("complete");
            TreeMap<Integer, byte[]> parts = uploads.remove(r.uploadId());
            List<Integer> declared = r.multipartUpload().parts().stream().map(CompletedPart::partNumber).toList();
            assertThat(declared).containsExactlyElementsOf(parts.keySet());
            int total = parts.values().stream().mapToInt(b -> b.length).sum();
            byte[] all = new byte[total];
            int at = 0;
            for (byte[] p : parts.values()) {
                System.arraycopy(p, 0, all, at, p.length);
                at += p.length;
            }
            objects.put(r.key(), all);
            return CompleteMultipartUploadResponse.builder().build();
        }

        @Override
        public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest r) {
            aborts++;
            uploads.remove(r.uploadId());
            return AbortMultipartUploadResponse.builder().build();
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest r) {
            byte[] b = objects.get(r.key());
            if (b == null) throw NoSuchKeyException.builder().statusCode(404).message("missing").build();
            return HeadObjectResponse.builder().contentLength((long) b.length).lastModified(Instant.parse("2026-01-01T00:00:00Z")).build();
        }

        @Override
        public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest r) {
            calls.add("get");
            byte[] b = objects.get(r.key());
            if (b == null) throw NoSuchKeyException.builder().statusCode(404).message("missing").build();
            int from = 0;
            int to = b.length;
            if (r.range() != null) {
                ranges.add(r.range());
                String spec = r.range().substring("bytes=".length());
                from = Integer.parseInt(spec.substring(0, spec.indexOf('-')));
                String end = spec.substring(spec.indexOf('-') + 1);
                if (!end.isEmpty()) to = Integer.parseInt(end) + 1;
            }
            byte[] slice = Arrays.copyOfRange(b, from, to);
            InputStream in = new ByteArrayInputStream(slice);
            AbortableInputStream abortable = AbortableInputStream.create(in, () -> aborted++);
            return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) slice.length).build(), abortable);
        }

        @Override
        public DeleteObjectResponse deleteObject(DeleteObjectRequest r) {
            calls.add("delete");
            objects.remove(r.key());
            return DeleteObjectResponse.builder().build();
        }

        @Override
        public HeadBucketResponse headBucket(HeadBucketRequest r) {
            if (!bucketExists) throw NoSuchBucketException.builder().statusCode(404).message("no bucket").build();
            return HeadBucketResponse.builder().build();
        }

        @Override
        public CreateBucketResponse createBucket(CreateBucketRequest r) {
            calls.add("create-bucket");
            bucketExists = true;
            return CreateBucketResponse.builder().build();
        }

        private static byte[] read(RequestBody body) {
            try (InputStream in = body.contentStreamProvider().newStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    private static final String KEY = "media/abcdefghijklmnop.mp3";

    @Test
    void smallStreamsArePutInOneRequestWithoutAcls() {
        FakeS3 s3 = new FakeS3();
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        storage.put("private/abcdefghijklmnopqrstuvwx.bin", new ByteArrayInputStream(bytes(1000)), "application/octet-stream");
        assertThat(s3.calls).containsExactly("put");
        assertThat(s3.puts.getFirst().acl()).isNull(); // never public-read: the bucket stays private
        assertThat(s3.puts.getFirst().aclAsString()).isNull();
        assertThat(s3.objects.get("private/abcdefghijklmnopqrstuvwx.bin")).isEqualTo(bytes(1000));
    }

    @Test
    void largeStreamsUseMultipartUploadInPartSizedChunks() {
        FakeS3 s3 = new FakeS3();
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        int size = S3StorageService.PART_SIZE * 2 + 12345;
        storage.put(KEY, new ByteArrayInputStream(bytes(size)), "audio/mpeg");
        assertThat(s3.calls).containsExactly("create-mpu", "part", "part", "part", "complete");
        assertThat(s3.objects.get(KEY)).isEqualTo(bytes(size));
        assertThat(s3.aborts).isZero();
    }

    @Test
    void aFailedMultipartUploadIsAborted() {
        FakeS3 s3 = new FakeS3();
        s3.failParts = true;
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        assertThatThrownBy(() -> storage.put(KEY, new ByteArrayInputStream(bytes(S3StorageService.PART_SIZE * 2)), "audio/mpeg"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(s3.aborts).isEqualTo(1);
        assertThat(s3.objects).doesNotContainKey(KEY);
    }

    @Test
    void rangeRequestsBecomeS3RangedGetsAndPartialReadsAbort() throws Exception {
        FakeS3 s3 = new FakeS3();
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        byte[] data = bytes(100_000);
        storage.put(KEY, data, "audio/mpeg");
        s3.calls.clear();

        Resource r = storage.resource(KEY).orElseThrow();
        assertThat(r.contentLength()).isEqualTo(100_000);
        assertThat(s3.calls).isEmpty(); // HEAD only: no bytes fetched until someone reads

        // Exactly what Spring MVC does for "Range: bytes=50000-50099" on a ResponseEntity<Resource>.
        var regions = HttpRange.toResourceRegions(HttpRange.parseRanges("bytes=50000-50099"), r);
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        new ResourceRegionHttpMessageConverter().write(regions.getFirst(), MediaType.parseMediaType("audio/mpeg"), out);
        assertThat(out.getBodyAsBytes()).isEqualTo(Arrays.copyOfRange(data, 50_000, 50_100));
        assertThat(out.getHeaders().getFirst("Content-Range")).isEqualTo("bytes 50000-50099/100000");
        assertThat(s3.ranges).containsExactly("bytes=50000-"); // seeked server-side, not by downloading the prefix
        assertThat(s3.aborted).isEqualTo(1); // the rest of the object isn't drained

        // A full read starts at 0 without a Range header and closes cleanly.
        try (InputStream in = r.getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
        assertThat(s3.ranges).hasSize(1);
        assertThat(s3.aborted).isEqualTo(1);
    }

    @Test
    void missingObjectsAndUnsafeKeysAreEmpty() {
        FakeS3 s3 = new FakeS3();
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        assertThat(storage.resource("media/nothinghere1234.png")).isEmpty();
        assertThat(storage.exists("media/nothinghere1234.png")).isFalse();
        assertThat(storage.get("media/nothinghere1234.png")).isEmpty();
        assertThat(storage.resource("../etc/passwd")).isEmpty();
        assertThatThrownBy(() -> storage.put("../../x.png", new byte[1], "image/png")).isInstanceOf(IllegalArgumentException.class);

        storage.put("media/abcdefghij12.png", new byte[]{1, 2, 3}, "image/png");
        assertThat(storage.exists("media/abcdefghij12.png")).isTrue();
        storage.delete("media/abcdefghij12.png");
        assertThat(storage.exists("media/abcdefghij12.png")).isFalse();
    }

    @Test
    void healthAndBucketCreation() {
        FakeS3 s3 = new FakeS3();
        s3.bucketExists = false;
        S3StorageService storage = new S3StorageService(s3, "howlers", S3StorageService.PART_SIZE);
        assertThat(storage.healthy()).isFalse();
        storage.ensureBucket();
        assertThat(s3.calls).contains("create-bucket");
        assertThat(storage.healthy()).isTrue();
        assertThatThrownBy(() -> new S3StorageService(s3, " ", S3StorageService.PART_SIZE)).isInstanceOf(IllegalStateException.class);
    }
}
