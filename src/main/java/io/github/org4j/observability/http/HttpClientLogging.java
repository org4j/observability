package io.github.org4j.observability.http;

import static io.github.org4j.observability.ObservabilityConstants.NO_BODIES;
import static io.github.org4j.observability.http.HttpConstants.NOT_LOGGED;

import io.github.org4j.masking.Masking;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Logs every client call in at most three lines: {@code →} with the masked URI and request body,
 * then {@code ←} with the status, time and masked response body, or {@code ✗} when the call fails.
 *
 * <p>Only a JSON, XML or form answer of up to {@code max-body-bytes} is read for the log; anything
 * else streams through, logged by its size. A request carrying the attribute {@link
 * io.github.org4j.observability.ObservabilityConstants#NO_BODIES NO_BODIES} is logged without
 * either body. Installed on every {@code RestClient.Builder}; a client built on its own request
 * factory adds the bean itself.
 */
public class HttpClientLogging implements ClientHttpRequestInterceptor {

  private static final Logger log = LoggerFactory.getLogger(HttpClientLogging.class);

  private final Masking masking;
  private final HttpLoggingProperties properties;

  public HttpClientLogging(Masking masking, HttpLoggingProperties properties) {
    this.masking = masking;
    this.properties = properties;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    var call = request.getMethod() + " " + masking.uri(request.getURI());
    var headers = request.getHeaders();
    var bodies = !request.getAttributes().containsKey(NO_BODIES);
    log.info(
        "→ {}{} | {}",
        call,
        WireLog.headers(masking, properties, headers.headerNames(), headers::getOrEmpty),
        bodies ? masking.body(body, type(headers)) : NOT_LOGGED);

    var started = System.nanoTime();
    ClientHttpResponse response;
    try {
      response = execution.execute(request, body);
    } catch (IOException e) {
      log.warn("✗ {} | {} ms | {}", call, WireLog.millisSince(started), e.toString());
      throw e;
    }

    var answer = response;
    String logged;
    var answerHeaders = response.getHeaders();
    var contentType = answerHeaders.getContentType();
    var length = answerHeaders.getContentLength();
    var max = properties.maxBodyBytes();
    if (!bodies) {
      logged = NOT_LOGGED;
    } else if (!WireLog.readable(contentType) || length > max) {
      logged = WireLog.size(length, contentType);
    } else {
      // Read at most one byte past the cap: a longer body is put back in front of the rest.
      var start = response.getBody().readNBytes(max + 1);
      if (start.length <= max) {
        logged = masking.body(start, type(answerHeaders));
        answer = new Read(response, new ByteArrayInputStream(start));
      } else {
        logged = WireLog.tooLong(max, contentType);
        answer =
            new Read(
                response,
                new SequenceInputStream(new ByteArrayInputStream(start), response.getBody()));
      }
    }

    var status = response.getStatusCode();
    var line = "← {} {} | {} ms | {}";
    if (status.isError()) {
      log.warn(line, status.value(), call, WireLog.millisSince(started), logged);
    } else {
      log.info(line, status.value(), call, WireLog.millisSince(started), logged);
    }
    return answer;
  }

  private static @Nullable String type(HttpHeaders headers) {
    var type = headers.getContentType();
    return type == null ? null : type.toString();
  }

  private record Read(ClientHttpResponse response, InputStream body) implements ClientHttpResponse {

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return response.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return response.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
      return response.getHeaders();
    }

    @Override
    public InputStream getBody() {
      return body;
    }

    @Override
    public void close() {
      response.close();
    }
  }
}
