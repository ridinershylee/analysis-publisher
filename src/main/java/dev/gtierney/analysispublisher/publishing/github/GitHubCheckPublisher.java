package dev.gtierney.analysispublisher.publishing.github;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.Iterables;
import dev.gtierney.analysispublisher.publishing.ReportPublisher;
import dev.gtierney.analysispublisher.publishing.github.model.CheckRun;
import dev.gtierney.analysispublisher.publishing.github.model.CheckRunConclusion;
import dev.gtierney.analysispublisher.publishing.github.serializer.GitHubChecksSerializerModule;
import dev.gtierney.analysispublisher.publishing.github.service.CheckRunWebService;
import edu.hm.hafner.analysis.Report;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

public class GitHubCheckPublisher implements ReportPublisher {

  private static final Logger logger = LogManager.getLogger(GitHubCheckPublisher.class);

  private final GitHubCheckPublisherConfig config;
  private final ResteasyClientBuilder clientBuilder;

  public GitHubCheckPublisher(GitHubCheckPublisherConfig config) {
    this(config, new ResteasyClientBuilder());
  }

  public GitHubCheckPublisher(
      GitHubCheckPublisherConfig config, ResteasyClientBuilder clientBuilder) {
    this.config = config;
    this.clientBuilder = clientBuilder;
  }

  @Override
  public void publish(Report report) {
    var authenticator = new TokenAuthentication(config.getToken());
    var client = clientBuilder.register(configureJackson()).register(authenticator).build();
    var target = client.target(config.getApiUri());
    var checkRunService = target.proxy(CheckRunWebService.class);

    var repository = config.getRepository();
    var headSha = config.getHeadSha();

    logger.info("Publishing report for {}@{}", repository, headSha);

    var runName = config.getCheckName();
    var runTitle = config.getCheckTitle();
    var run = new CheckRun(runName, headSha, runTitle, "in-progress");

    // Normalize the file names to paths relative to the workspace so annotations
    // display correctly in diffs.
    var workspace = config.getWorkspacePath();
    workspace.ifPresent(
        workspaceDir -> {
          if (!workspaceDir.endsWith("/")) {
            workspaceDir += "/";
          }

          for (var issue : report) {
            var fullPath = issue.getFileName();
            if (fullPath.startsWith(workspaceDir)) {
              issue.setFileName(fullPath.substring(workspaceDir.length()));
            }
          }
        });

    try {
      var runCreationResult = checkRunService.create(repository, run);
      var runId = runCreationResult.getId();

      for (var issueBatch : Iterables.partition(report, CheckRun.MAX_ANNOTATIONS)) {
        checkRunService.update(repository, runId, run.withIssues(issueBatch));
      }

      checkRunService.update(repository, runId, run.completed(CheckRunConclusion.NEUTRAL));
    } catch (ClientErrorException ex) {
      var response = ex.getResponse();
      var responseValue = response.readEntity(String.class);

      logger.error("Couldn't send data to GitHub API", ex);
      logger.error("Response is {}", responseValue);
    }
  }

  private ResteasyJackson2Provider configureJackson() {
    ResteasyJackson2Provider jacksonProvider = new ResteasyJackson2Provider();
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new GitHubChecksSerializerModule());
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    return jacksonProvider;
  }

  private static class TokenAuthentication implements ClientRequestFilter {
    private final String authHeader;

    TokenAuthentication(String token) {
      authHeader = String.format("Bearer %s", token);
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
      logger.error("Requesting {}", requestContext.getUri());
      requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, authHeader);
    }
  }
}
