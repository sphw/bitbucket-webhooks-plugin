package nl.eernie.bitbucket.api;

import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestUpdatedEvent;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestRef;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.httpclient.api.HttpClient;
import com.atlassian.httpclient.api.Request;
import com.atlassian.httpclient.api.Response;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import nl.eernie.bitbucket.events.BitbucketServerPullRequestEvent;
import nl.eernie.bitbucket.events.EventType;
import nl.eernie.bitbucket.model.pullrequest.BitbucketServerPullRequest;
import nl.eernie.bitbucket.model.pullrequest.BitbucketServerPullRequestSource;
import nl.eernie.bitbucket.model.repository.BitbucketServerProject;
import nl.eernie.bitbucket.model.repository.BitbucketServerRepository;
import nl.eernie.bitbucket.persistence.PersistenceManager;
import nl.eernie.bitbucket.persistence.WebHookConfiguration;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Component
public class PullRequestListener implements DisposableBean
{
	private EventPublisher eventPublisher;
	private HttpClient httpClient;
	private PersistenceManager persistenceManager;

	@Autowired
	public PullRequestListener(@ComponentImport EventPublisher eventPublisher, @ComponentImport HttpClient httpClient, PersistenceManager persistenceManager)
	{
		this.eventPublisher = eventPublisher;
		this.httpClient = httpClient;
		this.persistenceManager = persistenceManager;
		eventPublisher.register(this);
	}

	@EventListener
	public void createdEvent(PullRequestOpenedEvent event) throws IOException, ExecutionException, InterruptedException
	{
		sendPullRequestEvent(event, EventType.PULL_REQUEST_CREATED);
	}

	@EventListener
	public void updatedEvent(PullRequestUpdatedEvent event) throws IOException, ExecutionException, InterruptedException
	{
		sendPullRequestEvent(event, EventType.PULL_REQUEST_UPDATED);
	}

	private void sendPullRequestEvent(PullRequestEvent event, EventType eventType) throws IOException, ExecutionException, InterruptedException
	{
		Repository repo = event.getPullRequest().getToRef().getRepository();
		WebHookConfiguration[] webHookConfigurations = persistenceManager.getWebHookConfigurations(repo);

		BitbucketServerPullRequestEvent pullRequestEvent = createPullrequestEvent(event);

		Map<String, String> header = new HashMap<>();
		header.put("X-Event-Key", eventType.getHeaderValue());
		header.put("X-Bitbucket-Type", "server");

		ObjectMapper mapper = new ObjectMapper();
		for (WebHookConfiguration webHookConfiguration : webHookConfigurations)
		{
			if (!webHookConfiguration.isEnabled())
			{
				continue;
			}
			Request.Builder builder = httpClient.newRequest(webHookConfiguration.getURL());
			builder.setHeaders(header);
			builder.setContentType(MediaType.APPLICATION_JSON_VALUE);
			Response response = builder.setEntity(mapper.writeValueAsString(pullRequestEvent)).post().get();
			System.out.println(response.getEntity());
			System.out.println(response.getHeaders());
			System.out.println(response.getStatusCode());
		}
	}

	private BitbucketServerPullRequestEvent createPullrequestEvent(PullRequestEvent event)
	{
		BitbucketServerPullRequestEvent pullRequestEvent = new BitbucketServerPullRequestEvent();
		pullRequestEvent.setPullrequest(createPullrequest(event.getPullRequest()));
		pullRequestEvent.setRepository(createRepository(event.getPullRequest().getToRef().getRepository()));
		return pullRequestEvent;
	}

	private BitbucketServerRepository createRepository(Repository repository)
	{
		BitbucketServerRepository repoType = new BitbucketServerRepository();
		repoType.setProject(createProject(repository.getProject()));
		repoType.setPublic(repository.isPublic());
		repoType.setScmId(repository.getScmId());
		repoType.setSlug(repository.getSlug());
		return repoType;
	}

	private BitbucketServerProject createProject(Project project)
	{
		BitbucketServerProject serverProject = new BitbucketServerProject();
		serverProject.setName(project.getName());
		serverProject.setKey(project.getKey());
		return serverProject;
	}

	private BitbucketServerPullRequest createPullrequest(PullRequest pullRequest)
	{
		BitbucketServerPullRequest pullRequestType = new BitbucketServerPullRequest();
		pullRequestType.setId(String.valueOf(pullRequest.getId()));
		pullRequestType.setFromRef(createSource(pullRequest.getFromRef()));
		pullRequestType.setToRef(createSource(pullRequest.getToRef()));
		return pullRequestType;
	}

	private BitbucketServerPullRequestSource createSource(PullRequestRef pullRequestRef)
	{
		BitbucketServerPullRequestSource source = new BitbucketServerPullRequestSource();
		source.setDisplayId(pullRequestRef.getDisplayId());
		source.setLatestCommit(pullRequestRef.getLatestCommit());
		source.setRepository(createRepository(pullRequestRef.getRepository()));
		return source;
	}

	@Override
	public void destroy() throws Exception
	{
		eventPublisher.unregister(this);
	}
}
