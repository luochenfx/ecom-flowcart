package io.autocommerce.content.media;

import io.autocommerce.core.step.MediaProcessRequest;
import io.autocommerce.core.step.MediaProcessResult;
import io.autocommerce.core.step.MediaProcessor;
import io.autocommerce.core.step.StepExecutionException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * v1 {@link MediaProcessor} 实现：**归档**——把来源媒体拉取到自有存储，回填 storage_ref 并推进
 * processing_state（RAW → DOWNLOADED）。
 *
 * <p>范围诚实说明：v1 只做"把图拿下来"，**不做**去水印 / 格式转换 / 尺寸裁剪（需引入图像库，
 * 属规格 0006 §8 媒体处理的后续档位）；{@code media.localize} 生成式（LOCALIZED）v1 预留不注册。
 * 换真实图像流水线时同接口替换实现，Step 声明与编排不改。
 *
 * <p>失败一律收敛为 {@link StepExecutionException}（由 media.process Step 的硬依赖语义决定内容链
 * failed）；本类不重试（编排层 RetryPolicy 负责）。
 */
public final class ArchiveMediaProcessor implements MediaProcessor {

    /** v1 支持的处理操作标识。 */
    public static final String OPERATION_ARCHIVE = "archive";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_EXTENSION = ".bin";

    private final Path root;
    private final HttpClient http;

    public ArchiveMediaProcessor(Path root) {
        this(root, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** 测试/高级装配可注入 HttpClient。 */
    public ArchiveMediaProcessor(Path root, HttpClient http) {
        if (root == null) {
            throw new IllegalArgumentException("媒体归档根目录必填");
        }
        this.root = root;
        this.http = http;
    }

    @Override
    public MediaProcessResult process(MediaProcessRequest request) throws StepExecutionException {
        if (request == null || blank(request.mediaId()) || blank(request.sourceUrl())) {
            throw new StepExecutionException("MediaProcessRequest 需 mediaId 与 sourceUrl");
        }
        if (request.operation() != null && !OPERATION_ARCHIVE.equals(request.operation())) {
            throw new StepExecutionException("v1 仅支持 " + OPERATION_ARCHIVE + " 操作，收到: "
                    + request.operation());
        }
        try {
            Files.createDirectories(root);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(request.sourceUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new StepExecutionException("媒体拉取 HTTP " + response.statusCode() + ": "
                        + request.sourceUrl());
            }
            Path target = root.resolve(request.mediaId() + extensionOf(request.sourceUrl()));
            Files.write(target, response.body());
            return new MediaProcessResult(request.mediaId(), target.toAbsolutePath().toString(),
                    request.variantLocale());
        } catch (HttpTimeoutException e) {
            throw new StepExecutionException("媒体拉取超时（" + REQUEST_TIMEOUT.toSeconds() + "s）: "
                    + request.sourceUrl(), e);
        } catch (IOException e) {
            throw new StepExecutionException("媒体归档失败: " + request.sourceUrl()
                    + "（" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepExecutionException("媒体归档被中断: " + request.sourceUrl(), e);
        }
    }

    private static String extensionOf(String url) {
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return DEFAULT_EXTENSION;
        }
        String extension = path.substring(dot).toLowerCase(Locale.ROOT);
        return extension.length() <= 6 ? extension : DEFAULT_EXTENSION;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
