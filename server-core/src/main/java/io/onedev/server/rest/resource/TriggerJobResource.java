package io.onedev.server.rest.resource;

import io.onedev.commons.utils.StringUtils;
import io.onedev.server.entitymanager.AccessTokenManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.git.GitUtils;
import io.onedev.server.job.JobManager;
import io.onedev.server.model.Project;
import io.onedev.server.rest.InvalidParamException;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.security.SecurityUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.util.ThreadContext;
import org.eclipse.jgit.revwalk.RevCommit;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotEmpty;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Api(description="This resource provides an alternative way to run job by passing all parameters via url")
@Path("/trigger-job")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
@Singleton
public class TriggerJobResource {

	private static final String PARAM_PROJECT = "project";
	
	private static final String PARAM_BRANCH = "branch";
	
	private static final String PARAM_TAG = "tag";
	
	private static final String PARAM_JOB = "job";
	
	private static final String PARAM_ACCESS_TOKEN = "access-token";
	
	private static final String PARAM_REBUILD_IF_FINISHED = "rebuild-if-finished";
	
	private static final String DESCRIPTION = "Trigger specified job. Query parameters other than listed below "
			+ "will be interpreted as job params";
	
	private static final String REF_DESCRIPTION = "Specify branch or tag to be triggered against. If none specified, default branch will be used";
	
	private static final String ACCESS_TOKEN_DESCRIPTION = "OneDev access token with permission to trigger the job";
	
	private final JobManager jobManager;
	
	private final ProjectManager projectManager;
	
	private final AccessTokenManager accessTokenManager;
	
	@Inject
	public TriggerJobResource(JobManager jobManager, ProjectManager projectManager, AccessTokenManager accessTokenManager) {
		this.jobManager = jobManager;
		this.projectManager = projectManager;
		this.accessTokenManager = accessTokenManager;
	}

	@Api(order=100, description=DESCRIPTION)
	@GET
    public Long triggerJobViaGet(
    		@Api(description="Path of the project") @QueryParam(PARAM_PROJECT) @NotEmpty String projectPath, 
    		@Api(description=REF_DESCRIPTION) @QueryParam(PARAM_BRANCH) @Nullable String branch, 
    		@Api(description=REF_DESCRIPTION) @QueryParam(PARAM_TAG) @Nullable String tag, 
    		@QueryParam(PARAM_JOB) @NotEmpty String job, 
    		@Api(description=ACCESS_TOKEN_DESCRIPTION) @QueryParam(PARAM_ACCESS_TOKEN) @NotEmpty String accessToken, 
    		@Context UriInfo uriInfo) {
		return triggerJob(projectPath, branch, tag, job, accessToken, uriInfo);
    }
	
	@Api(order=100, description=DESCRIPTION)
	@POST
    public Long triggerJobViaPost(
    		@Api(description="Path of the project") @QueryParam(PARAM_PROJECT) @NotEmpty String projectPath, 
    		@Api(description=REF_DESCRIPTION) @QueryParam(PARAM_BRANCH) @Nullable String branch, 
    		@Api(description=REF_DESCRIPTION) @QueryParam(PARAM_TAG) @Nullable String tag, 
    		@QueryParam(PARAM_JOB) @NotEmpty String job,
			@Api(description=ACCESS_TOKEN_DESCRIPTION) @QueryParam(PARAM_ACCESS_TOKEN) @NotEmpty String accessToken, 
    		@Context UriInfo uriInfo) {
		return triggerJob(projectPath, branch, tag, job, accessToken, uriInfo);
    }

    private Long triggerJob(String projectPath, @Nullable String branch, @Nullable String tag, String job,
							String accessTokenValue, UriInfo uriInfo) {
		Project project = projectManager.findByPath(projectPath);
		if (project == null)
			throw new InvalidParamException("Project not found: " + projectPath);

		var accessToken = accessTokenManager.findByValue(accessTokenValue);
		if (accessToken == null)
			throw new InvalidParamException("Invalid access token");
		
		ThreadContext.bind(accessToken.asSubject());
		try {
			if (!SecurityUtils.canRunJob(project, job))		
				throw new UnauthorizedException();

			if (StringUtils.isNotBlank(branch) && StringUtils.isNotBlank(tag)) 
				throw new InvalidParamException("Either branch or tag should be specified, but not both");
			
			String refName;
			if (branch != null)
				refName = GitUtils.branch2ref(branch);
			else if (tag != null)
				refName = GitUtils.tag2ref(tag);
			else
				refName = GitUtils.branch2ref(project.getDefaultBranch());
			
			RevCommit commit = project.getRevCommit(refName, false);
			if (commit == null)
				throw new InvalidParamException("Ref not found: " + refName);
			
			Map<String, List<String>> jobParams = new HashMap<>();
			for (Map.Entry<String, List<String>> entry: uriInfo.getQueryParameters().entrySet()) {
				if (!entry.getKey().equals(PARAM_PROJECT) && !entry.getKey().equals(PARAM_BRANCH)
						&& !entry.getKey().equals(PARAM_TAG) && !entry.getKey().equals(PARAM_JOB) 
						&& !entry.getKey().equals(PARAM_ACCESS_TOKEN)
						&& !entry.getKey().equals(PARAM_REBUILD_IF_FINISHED)) {
					jobParams.put(entry.getKey(), entry.getValue());
				}
			}
			
			var build = jobManager.submit(project, commit.copy(), job, 
					jobParams, refName, SecurityUtils.getUser(), null, 
					null, "Triggered via restful api");
			if (build.isFinished())
				jobManager.resubmit(build, "Rebuild via restful api");
			return build.getId();
		} finally {
			ThreadContext.unbindSubject();
		}
    }
	
}
