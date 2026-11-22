package io.github.org4j.observability.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.org4j.masking.Masking;
import io.github.org4j.masking.MaskingRules;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

// On a real container: reading a form body for the log must not take the parameters from the app.
@ExtendWith(OutputCaptureExtension.class)
class HttpServerLoggingOnTomcatTest {

  private WebServer server;

  @BeforeEach
  void start() {
    var masking = new Masking(MaskingRules.of(List.of("pan")));
    var filter =
        new HttpServerLoggingFilter(
            masking, new HttpLoggingProperties(true, 4000, null, null, null));
    var factory = new TomcatServletWebServerFactory(0);
    server =
        factory.getWebServer(
            context -> {
              context.addFilter("logging", filter).addMappingForUrlPatterns(null, false, "/*");
              context.addServlet("echo", new Echo()).addMapping("/*");
            });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void theAppStillGetsTheFormParameters(CapturedOutput output) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/pay?order=7"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "pan=8600301234567890&amount=100&note=two%20words"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.body()).isEqualTo("order=7 pan=8600301234567890 amount=100 note=two words");
    assertThat(output)
        .contains("→ POST /pay?order=7 | pan=860030......7890&amount=100&note=two%20words")
        .doesNotContain("pan=8600301234567890&");
  }

  static final class Echo extends HttpServlet {

    @Serial private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      response.setContentType("text/plain");
      response
          .getWriter()
          .write(
              "order="
                  + request.getParameter("order")
                  + " pan="
                  + request.getParameter("pan")
                  + " amount="
                  + request.getParameter("amount")
                  + " note="
                  + request.getParameter("note"));
    }
  }
}
