package io.github.org4j.observability.http;

import static io.github.org4j.observability.http.HttpConstants.NOT_LOGGED;

import io.github.org4j.masking.Masking;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs every request the service serves, when {@code org4j.observability.logging.http.enabled}:
 * {@code →} with the masked URI, the included headers and the masked body, then {@code ←} with the
 * status, time and masked response body, or {@code ✗} when the request fails.
 *
 * <p>Only a JSON, XML or form body is logged; anything else is logged by its size, and a body
 * longer than {@code max-body-bytes} by its type. At most one byte past the cap is held in memory.
 */
public class HttpServerLoggingFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger log = LoggerFactory.getLogger(HttpServerLoggingFilter.class);

  private final Masking masking;
  private final HttpLoggingProperties properties;

  public HttpServerLoggingFilter(Masking masking, HttpLoggingProperties properties) {
    this.masking = masking;
    this.properties = properties;
  }

  // After RequestIdFilter, so the response already names its trace.
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 3;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return properties.isExcluded(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse servletResponse, FilterChain chain)
      throws ServletException, IOException {
    var call = request.getMethod() + " " + maskedUri(request);
    var noBody = properties.isNoBody(request.getRequestURI());
    var contentType = request.getContentType();
    var max = properties.maxBodyBytes();

    var served = request;
    String requestBody;
    if (noBody) {
      requestBody = NOT_LOGGED;
    } else if (!WireLog.readable(contentType)) {
      requestBody = WireLog.size(request.getContentLengthLong(), contentType);
    } else {
      // Read at most one byte past the cap: a longer body is put back in front of the rest.
      var start = request.getInputStream().readNBytes(max + 1);
      if (start.length <= max) {
        requestBody = masking.body(start, contentType);
        served = new Replayed(request, new ByteArrayInputStream(start));
      } else {
        requestBody = WireLog.tooLong(max, contentType);
        served =
            new Replayed(
                request,
                new SequenceInputStream(new ByteArrayInputStream(start), request.getInputStream()));
      }
    }
    var headers =
        WireLog.headers(
            masking,
            properties,
            listed(request.getHeaderNames()),
            name -> listed(request.getHeaders(name)));
    log.info("→ {}{} | {}", call, headers, requestBody);

    var response = new ContentCachingResponseWrapper(servletResponse);
    var started = System.nanoTime();
    try {
      chain.doFilter(served, response);
      var status = response.getStatus();
      var line = "← {} {} | {} ms | {}";
      var responseBody = noBody ? NOT_LOGGED : responseBody(response, max);
      if (status >= 400) {
        log.warn(line, status, call, WireLog.millisSince(started), responseBody);
      } else {
        log.info(line, status, call, WireLog.millisSince(started), responseBody);
      }
    } catch (IOException | ServletException | RuntimeException e) {
      log.warn("✗ {} | {} ms | {}", call, WireLog.millisSince(started), e.toString());
      throw e;
    } finally {
      response.copyBodyToResponse();
    }
  }

  private String maskedUri(HttpServletRequest request) {
    var query = request.getQueryString();
    var whole = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    try {
      return masking.uri(URI.create(whole));
    } catch (IllegalArgumentException e) {
      return whole;
    }
  }

  // A container may refuse to list headers, and return null.
  private static List<String> listed(@Nullable Enumeration<String> values) {
    return values == null ? List.of() : Collections.list(values);
  }

  private String responseBody(ContentCachingResponseWrapper response, int max) {
    var contentType = response.getContentType();
    if (!WireLog.readable(contentType)) {
      return WireLog.size(response.getContentSize(), contentType);
    }
    var body = response.getContentAsByteArray();
    return body.length > max ? WireLog.tooLong(max, contentType) : masking.body(body, contentType);
  }

  private static final class Replayed extends HttpServletRequestWrapper {

    private final InputStream body;

    Replayed(HttpServletRequest request, InputStream body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ServletInputStream() {
        private boolean finished;

        @Override
        public boolean isFinished() {
          return finished;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int read() throws IOException {
          var next = body.read();
          finished = next < 0;
          return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          var count = body.read(buffer, offset, length);
          finished = count < 0;
          return count;
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      var encoding = getCharacterEncoding();
      var charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }
}
