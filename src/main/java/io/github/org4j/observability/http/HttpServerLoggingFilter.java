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
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
    } else if (isForm(contentType)) {
      // Read whole, as the container would to parse it: the app's parameters come from it now.
      var form = request.getInputStream().readAllBytes();
      requestBody =
          form.length <= max ? masking.body(form, contentType) : WireLog.tooLong(max, contentType);
      served = new Replayed(request, new ByteArrayInputStream(form), parameters(request, form));
    } else {
      // Read at most one byte past the cap: a longer body is put back in front of the rest.
      var start = request.getInputStream().readNBytes(max + 1);
      if (start.length <= max) {
        requestBody = masking.body(start, contentType);
        served = new Replayed(request, new ByteArrayInputStream(start), null);
      } else {
        requestBody = WireLog.tooLong(max, contentType);
        served =
            new Replayed(
                request,
                new SequenceInputStream(new ByteArrayInputStream(start), request.getInputStream()),
                null);
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

  private static boolean isForm(String contentType) {
    return contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
  }

  // Once the body is taken, the container parses only the query string: the body's pairs follow.
  // A pair that does not decode is skipped, as the container does.
  private static Map<String, String[]> parameters(HttpServletRequest request, byte[] form) {
    var charset = charset(request);
    var merged = new LinkedHashMap<String, List<String>>();
    request
        .getParameterMap()
        .forEach(
            (name, values) ->
                merged.computeIfAbsent(name, n -> new ArrayList<>()).addAll(List.of(values)));
    Pattern.compile("&")
        .splitAsStream(new String(form, charset))
        .forEach(pair -> add(merged, pair, charset));
    var parameters = new LinkedHashMap<String, String[]>();
    merged.forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
    return Collections.unmodifiableMap(parameters);
  }

  private static void add(Map<String, List<String>> parameters, String pair, Charset charset) {
    var at = pair.indexOf('=');
    String name;
    String value;
    try {
      name = URLDecoder.decode(at < 0 ? pair : pair.substring(0, at), charset);
      value = at < 0 ? "" : URLDecoder.decode(pair.substring(at + 1), charset);
    } catch (IllegalArgumentException malformed) {
      return;
    }
    if (!name.isEmpty()) {
      parameters.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
    }
  }

  private static Charset charset(HttpServletRequest request) {
    var encoding = request.getCharacterEncoding();
    return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
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
    private final @Nullable Map<String, String[]> parameters;

    Replayed(
        HttpServletRequest request, InputStream body, @Nullable Map<String, String[]> parameters) {
      super(request);
      this.body = body;
      this.parameters = parameters;
    }

    @Override
    public @Nullable String getParameter(String name) {
      if (parameters == null) {
        return super.getParameter(name);
      }
      var values = parameters.get(name);
      return values == null ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      return parameters == null ? super.getParameterMap() : parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return parameters == null
          ? super.getParameterNames()
          : Collections.enumeration(parameters.keySet());
    }

    @Override
    public String @Nullable [] getParameterValues(String name) {
      if (parameters == null) {
        return super.getParameterValues(name);
      }
      var values = parameters.get(name);
      return values == null ? null : values.clone();
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
      return new BufferedReader(new InputStreamReader(getInputStream(), charset(this)));
    }
  }
}
