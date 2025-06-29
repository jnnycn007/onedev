package io.onedev.server.rest.resource;

import static io.onedev.server.util.DateUtils.parseISO8601Date;
import static io.onedev.server.util.DateUtils.toLocalDate;
import static java.time.ZoneId.systemDefault;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.authz.UnauthorizedException;
import org.hibernate.criterion.Restrictions;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.entitymanager.IterationManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.git.GitContribution;
import io.onedev.server.git.GitContributor;
import io.onedev.server.model.BaseAuthorization;
import io.onedev.server.model.GroupAuthorization;
import io.onedev.server.model.Iteration;
import io.onedev.server.model.Project;
import io.onedev.server.model.ProjectLabel;
import io.onedev.server.model.UserAuthorization;
import io.onedev.server.model.support.NamedCodeCommentQuery;
import io.onedev.server.model.support.NamedCommitQuery;
import io.onedev.server.model.support.WebHook;
import io.onedev.server.model.support.build.ProjectBuildSetting;
import io.onedev.server.model.support.code.BranchProtection;
import io.onedev.server.model.support.code.TagProtection;
import io.onedev.server.model.support.issue.ProjectIssueSetting;
import io.onedev.server.model.support.pack.ProjectPackSetting;
import io.onedev.server.model.support.pullrequest.ProjectPullRequestSetting;
import io.onedev.server.persistence.dao.EntityCriteria;
import io.onedev.server.rest.InvalidParamException;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.rest.resource.support.RestConstants;
import io.onedev.server.search.entity.project.ProjectQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.DateUtils;
import io.onedev.server.util.facade.ProjectFacade;
import io.onedev.server.web.UrlManager;
import io.onedev.server.web.page.project.setting.ContributedProjectSetting;
import io.onedev.server.xodus.CommitInfoManager;

@Api(order=1000)
@Path("/projects")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Singleton
public class ProjectResource {

	private final ProjectManager projectManager;
	
	private final IterationManager iterationManager;
	
	private final CommitInfoManager commitInfoManager;
	
	private final UrlManager urlManager;
	
	@Inject
	public ProjectResource(ProjectManager projectManager, IterationManager iterationManager, 
			CommitInfoManager commitInfoManager, UrlManager urlManager) {
		this.projectManager = projectManager;
		this.iterationManager = iterationManager;
		this.commitInfoManager = commitInfoManager;
		this.urlManager = urlManager;
	}
	
	@Api(order=100)
	@Path("/{projectId}")
    @GET
    public Project getBasicInfo(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canAccessProject(project))
			throw new UnauthorizedException();
    	return project;
    }
	
	@Api(order=150)
	@Path("/{projectId}/clone-url")
    @GET
    public CloneUrl getCloneURL(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canAccessProject(project))
			throw new UnauthorizedException();

    	CloneUrl cloneUrl = new CloneUrl();
    	cloneUrl.setHttp(urlManager.cloneUrlFor(project, false));
    	cloneUrl.setSsh(urlManager.cloneUrlFor(project, true));
    	
    	return cloneUrl;
    }
	
	@Api(order=200)
	@Path("/{projectId}/setting")
    @GET
    public ProjectSetting getSetting(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canManageProject(project)) 
			throw new UnauthorizedException();
		ProjectSetting setting = new ProjectSetting();
		setting.branchProtections = project.getBranchProtections();
		setting.tagProtections = project.getTagProtections();
		setting.buildSetting = project.getBuildSetting();
		setting.packSetting = project.getPackSetting();
		setting.issueSetting = project.getIssueSetting();
		setting.namedCodeCommentQueries = project.getNamedCodeCommentQueries();
		setting.namedCommitQueries = project.getNamedCommitQueries();
		setting.pullRequestSetting = project.getPullRequestSetting();
		setting.webHooks = project.getWebHooks();
		setting.contributedSettings.addAll(project.getContributedSettings().values());
		return setting;
    }
	
	@Api(order=300)
	@Path("/{projectId}/forks")
    @GET
    public Collection<Project> getForks(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canAccessProject(project)) 
			throw new UnauthorizedException();
    	return project.getForks();
    }

	@Api(order=350, description = "A base authorization corresponds to a default role. It can be added/removed via <a href='/~help/api/io.onedev.server.rest.resource.BaseAuthorizationResource'>base authorizations resource</a>")
	@Path("/{projectId}/base-authorizations")
    @GET
    public Collection<BaseAuthorization> getBaseAuthorizations(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canManageProject(project)) 
			throw new UnauthorizedException();
    	return project.getBaseAuthorizations();
    }
		
	@Api(order=400)
	@Path("/{projectId}/group-authorizations")
    @GET
    public Collection<GroupAuthorization> getGroupAuthorizations(@PathParam("projectId") Long projectId) {
		var project = projectManager.load(projectId);
		if (!SecurityUtils.canManageProject(project))
			throw new UnauthorizedException();
    	return project.getGroupAuthorizations();
    }

	@Api(order=500)
	@Path("/{projectId}/user-authorizations")
    @GET
    public Collection<UserAuthorization> getUserAuthorizations(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canManageProject(project)) 
			throw new UnauthorizedException();
    	return project.getUserAuthorizations();
    }

	@Api(order=600, description = "Get list of <a href='/~help/api/io.onedev.server.rest.ProjectLabelResource'>labels</a>")
	@Path("/{projectId}/labels")
	@GET
	public Collection<ProjectLabel> getLabels(@PathParam("projectId") Long projectId) {
		Project project = projectManager.load(projectId);
		if (!SecurityUtils.canAccessProject(project))
			throw new UnauthorizedException();
		return project.getLabels();
	}
	
	@Api(order=700)
	@GET
    public List<Project> queryBasicInfo(
    		@QueryParam("query") @Api(description="Syntax of this query is the same as in <a href='/~projects'>projects page</a>", example="\"Name\" is \"projectName\"") String query, 
    		@QueryParam("offset") @Api(example="0") int offset, 
    		@QueryParam("count") @Api(example="100") int count) {

		if (!SecurityUtils.isAdministrator() && count > RestConstants.MAX_PAGE_SIZE)
    		throw new InvalidParamException("Count should not be greater than " + RestConstants.MAX_PAGE_SIZE);

    	ProjectQuery parsedQuery;
		try {
			parsedQuery = ProjectQuery.parse(query);
		} catch (Exception e) {
			throw new InvalidParamException("Error parsing query", e);
		}
    	
    	return projectManager.query(parsedQuery, false, offset, count);
    }
	
	@Api(order=750)
	@Path("/{projectId}/iterations")
    @GET
    public List<Iteration> queryIterations(@PathParam("projectId") Long projectId, @QueryParam("name") String name,
										   @QueryParam("startBefore") @Api(exampleProvider="getDateExample", description="ISO 8601 date") String startBefore,
										   @QueryParam("startAfter") @Api(exampleProvider="getDateExample", description="ISO 8601 date") String startAfter,
										   @QueryParam("dueBefore") @Api(exampleProvider="getDateExample", description="ISO 8601 date") String dueBefore,
										   @QueryParam("dueAfter") @Api(exampleProvider="getDateExample", description="ISO 8601 date") String dueAfter,
										   @QueryParam("closed") Boolean closed, @QueryParam("offset") @Api(example="0") int offset,
										   @QueryParam("count") @Api(example="100") int count) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canAccessProject(project)) 
			throw new UnauthorizedException();

    	if (count > RestConstants.MAX_PAGE_SIZE)
    		throw new InvalidParamException("Count should not be greater than " + RestConstants.MAX_PAGE_SIZE);
    	
    	EntityCriteria<Iteration> criteria = EntityCriteria.of(Iteration.class);
    	criteria.add(Restrictions.in(Iteration.PROP_PROJECT, project.getSelfAndAncestors()));
    	if (name != null)
    		criteria.add(Restrictions.ilike(Iteration.PROP_NAME, name.replace('%', '*')));
    	if (startBefore != null)
    		criteria.add(Restrictions.le(Iteration.PROP_START_DAY, toLocalDate(parseISO8601Date(startBefore), systemDefault()).toEpochDay()));
    	if (startAfter != null)
    		criteria.add(Restrictions.ge(Iteration.PROP_START_DAY, toLocalDate(parseISO8601Date(startAfter), systemDefault()).toEpochDay()));
    	if (dueBefore != null)
    		criteria.add(Restrictions.le(Iteration.PROP_DUE_DAY, toLocalDate(parseISO8601Date(dueBefore), systemDefault()).toEpochDay()));
    	if (dueAfter != null)
    		criteria.add(Restrictions.ge(Iteration.PROP_DUE_DAY, toLocalDate(parseISO8601Date(dueAfter), systemDefault()).toEpochDay()));
    	if (closed != null)
    		criteria.add(Restrictions.eq(Iteration.PROP_CLOSED, closed));
    	
    	return iterationManager.query(criteria, offset, count);
    }
	
	@Api(order=760, description="Get top contributors on default branch")
	@Path("/{projectId}/top-contributors")
	@GET
    public List<GitContributor> getTopContributors(@PathParam("projectId") Long projectId, 
    		@QueryParam("type") @NotNull GitContribution.Type type, 
    		@QueryParam("sinceDate") @NotEmpty @Api(description="Since date of format <i>yyyy-MM-dd</i>") String since, 
    		@QueryParam("untilDate") @NotEmpty @Api(description="Until date of format <i>yyyy-MM-dd</i>") String until, 
    		@QueryParam("count") int count) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canAccessProject(project)) 
			throw new UnauthorizedException();
    	
    	if (count > RestConstants.MAX_PAGE_SIZE)
    		throw new InvalidParamException("Count should not be greater than " + RestConstants.MAX_PAGE_SIZE);
    	
    	int sinceDay = (int) LocalDate.parse(since).toEpochDay();
    	int untilDay = (int) LocalDate.parse(until).toEpochDay();
    	
    	return commitInfoManager.getTopContributors(project.getId(), count, type, sinceDay, untilDay);
    }
	
	@SuppressWarnings("unused")
	private static String getDateExample() {
		return DateUtils.formatISO8601Date(new Date());
	}
	
	@Api(order=800, description="Create new project")
    @POST
    public Long create(@NotNull @Valid Project project) {
		Project parent = project.getParent();
		
		checkProjectCreationPermission(parent);
	
		if (parent != null && project.isSelfOrAncestorOf(parent)) 
			throw new ExplicitException("Can not use current or descendant project as parent");
		
		checkProjectNameDuplication(project);
		
		projectManager.create(project);
    	
    	return project.getId();
    }

	@Api(order=850, description="Update projecty basic info of specified id")
	@Path("/{projectId}")
	@POST
	public Response updateBasicInfo(@PathParam("projectId") Long projectId, @NotNull @Valid Project project) {
		Project parent = project.getParent();
		Long oldParentId;
		if (project.getOldVersion() != null)
			oldParentId = ((ProjectFacade) project.getOldVersion()).getParentId();
		else
			oldParentId = null;

		if (!Objects.equals(oldParentId, Project.idOf(parent))) 
			checkProjectCreationPermission(parent);

		if (parent != null && project.isSelfOrAncestorOf(parent))
			throw new ExplicitException("Can not use current or descendant project as parent");

		checkProjectNameDuplication(project);

		if (!SecurityUtils.canManageProject(project)) {
			throw new UnauthorizedException();
		} else {
			projectManager.update(project);
		}

		return Response.ok().build();
	}
	
	private void checkProjectCreationPermission(@Nullable Project parent) {
		if (parent != null && !SecurityUtils.canCreateChildren(parent))
			throw new UnauthorizedException("Not authorized to create project under '" + parent.getPath() + "'");
		if (parent == null && !SecurityUtils.canCreateRootProjects())
			throw new UnauthorizedException("Not authorized to create root project");
	}
	
	private void checkProjectNameDuplication(Project project) {
		Project parent = project.getParent();
		Project projectWithSameName = projectManager.find(parent, project.getName());
		if (projectWithSameName != null && !projectWithSameName.equals(project)) {
			if (parent != null) {
				throw new ExplicitException("Name '" + project.getName() + "' is already used by another project under '"
						+ parent.getPath() + "'");
			} else {
				throw new ExplicitException("Name '" + project.getName() + "' is already used by another root project");
			}
		}
	}
	
	@Api(order=900, description="Update project setting")
	@Path("/{projectId}/setting")
    @POST
    public Response updateSetting(@PathParam("projectId") Long projectId, @NotNull ProjectSetting setting) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canManageProject(project)) 
			throw new UnauthorizedException();
		project.setBranchProtections(setting.branchProtections);
		project.setTagProtections(setting.tagProtections);
		project.setBuildSetting(setting.buildSetting);
		project.setPackSetting(setting.packSetting);
		project.setIssueSetting(setting.issueSetting);
		project.setNamedCodeCommentQueries(setting.namedCodeCommentQueries);
		project.setNamedCommitQueries(setting.namedCommitQueries);
		project.setPullRequestSetting(setting.pullRequestSetting);
		project.setWebHooks(setting.webHooks);
		var contributedSettings = new LinkedHashMap<String, ContributedProjectSetting>();
		for (var contributedSetting: setting.getContributedSettings())
			contributedSettings.put(contributedSetting.getClass().getName(), contributedSetting);
		project.setContributedSettings(contributedSettings);
		projectManager.update(project);
		return Response.ok().build();
    }
	
	@Api(order=1000)
	@Path("/{projectId}")
    @DELETE
    public Response delete(@PathParam("projectId") Long projectId) {
    	Project project = projectManager.load(projectId);
    	if (!SecurityUtils.canManageProject(project))
			throw new UnauthorizedException();
    	projectManager.delete(project);
    	return Response.ok().build();
    }
	
	public static class CloneUrl implements Serializable {
		
		private static final long serialVersionUID = 1L;

		private String http;
		
		private String ssh;

		public String getHttp() {
			return http;
		}

		public void setHttp(String http) {
			this.http = http;
		}

		public String getSsh() {
			return ssh;
		}

		public void setSsh(String ssh) {
			this.ssh = ssh;
		}
		
	}
	
	public static class ProjectSetting implements Serializable {
		
		private static final long serialVersionUID = 1L;
		
		private ArrayList<BranchProtection> branchProtections = new ArrayList<>();
		
		private ArrayList<TagProtection> tagProtections = new ArrayList<>();
		
		private ProjectIssueSetting issueSetting = new ProjectIssueSetting();
		
		private ProjectBuildSetting buildSetting = new ProjectBuildSetting();
		
		private ProjectPackSetting packSetting = new ProjectPackSetting();
		
		private ProjectPullRequestSetting pullRequestSetting = new ProjectPullRequestSetting();
		
		private ArrayList<NamedCommitQuery> namedCommitQueries = new ArrayList<>();
		
		private ArrayList<NamedCodeCommentQuery> namedCodeCommentQueries = new ArrayList<>();
		
		private ArrayList<WebHook> webHooks = new ArrayList<>();
		
		private ArrayList<ContributedProjectSetting> contributedSettings = new ArrayList<>();

		public ArrayList<BranchProtection> getBranchProtections() {
			return branchProtections;
		}

		public void setBranchProtections(ArrayList<BranchProtection> branchProtections) {
			this.branchProtections = branchProtections;
		}

		public ArrayList<TagProtection> getTagProtections() {
			return tagProtections;
		}

		public void setTagProtections(ArrayList<TagProtection> tagProtections) {
			this.tagProtections = tagProtections;
		}

		public ProjectIssueSetting getIssueSetting() {
			return issueSetting;
		}

		public void setIssueSetting(ProjectIssueSetting issueSetting) {
			this.issueSetting = issueSetting;
		}

		public ProjectBuildSetting getBuildSetting() {
			return buildSetting;
		}

		public void setBuildSetting(ProjectBuildSetting buildSetting) {
			this.buildSetting = buildSetting;
		}

		public ProjectPackSetting getPackSetting() {
			return packSetting;
		}

		public void setPackSetting(ProjectPackSetting packSetting) {
			this.packSetting = packSetting;
		}

		public ProjectPullRequestSetting getPullRequestSetting() {
			return pullRequestSetting;
		}

		public void setPullRequestSetting(ProjectPullRequestSetting pullRequestSetting) {
			this.pullRequestSetting = pullRequestSetting;
		}

		public ArrayList<NamedCommitQuery> getNamedCommitQueries() {
			return namedCommitQueries;
		}

		public void setNamedCommitQueries(ArrayList<NamedCommitQuery> namedCommitQueries) {
			this.namedCommitQueries = namedCommitQueries;
		}

		public ArrayList<NamedCodeCommentQuery> getNamedCodeCommentQueries() {
			return namedCodeCommentQueries;
		}

		public void setNamedCodeCommentQueries(ArrayList<NamedCodeCommentQuery> namedCodeCommentQueries) {
			this.namedCodeCommentQueries = namedCodeCommentQueries;
		}

		public ArrayList<WebHook> getWebHooks() {
			return webHooks;
		}

		public void setWebHooks(ArrayList<WebHook> webHooks) {
			this.webHooks = webHooks;
		}

		public ArrayList<ContributedProjectSetting> getContributedSettings() {
			return contributedSettings;
		}

		public void setContributedSettings(ArrayList<ContributedProjectSetting> contributedSettings) {
			this.contributedSettings = contributedSettings;
		}
		
	}
	
}
