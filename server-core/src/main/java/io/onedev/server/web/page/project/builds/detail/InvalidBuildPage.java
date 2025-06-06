package io.onedev.server.web.page.project.builds.detail;

import static io.onedev.server.web.translation.Translation._T;

import java.text.MessageFormat;

import javax.persistence.EntityNotFoundException;

import org.apache.wicket.Component;
import org.apache.wicket.Session;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.request.flow.RedirectToUrlException;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.google.common.base.Preconditions;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.model.Build;
import io.onedev.server.model.Project;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.WebSession;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.builds.ProjectBuildsPage;
import io.onedev.server.web.page.project.dashboard.ProjectDashboardPage;
import io.onedev.server.web.util.ConfirmClickModifier;

public class InvalidBuildPage extends ProjectPage {

	public static final String PARAM_BUILD = "build";
	
	private IModel<Build> buildModel;
	
	public InvalidBuildPage(PageParameters params) {
		super(params);
		
		buildModel = new LoadableDetachableModel<Build>() {

			@Override
			protected Build load() {
				Long buildNumber = params.get(PARAM_BUILD).toLong();
				Build build = OneDev.getInstance(BuildManager.class).find(getProject(), buildNumber);
				if (build == null)
					throw new EntityNotFoundException(MessageFormat.format(_T("Unable to find build #{0} in project {1}"), buildNumber, getProject()));
				Preconditions.checkState(!build.isValid());
				return build;
			}

		};
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		var build = getBuild();
		add(new Label("missingCommit", build.getCommitHash()));
		add(new Label("jobName", build.getJobName()));
		
		add(new Link<Void>("delete") {

			@Override
			public void onClick() {
				OneDev.getInstance(BuildManager.class).delete(getBuild());
				
				Session.get().success(MessageFormat.format(_T("Build #{0} deleted"), getBuild().getNumber()));
				
				String redirectUrlAfterDelete = WebSession.get().getRedirectUrlAfterDelete(Build.class);
				if (redirectUrlAfterDelete != null)
					throw new RedirectToUrlException(redirectUrlAfterDelete);
				else
					setResponsePage(ProjectBuildsPage.class, ProjectBuildsPage.paramsOf(getProject()));
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(SecurityUtils.canManageBuild(getBuild()));
			}
			
		}.add(new ConfirmClickModifier(MessageFormat.format(_T("Do you really want to delete build #{0}?"), getBuild().getNumber()))));
	}

	public static PageParameters paramsOf(Build build) {
		PageParameters params = ProjectPage.paramsOf(build.getProject());
		params.add(PARAM_BUILD, build.getNumber());
		return params;
	}
	
	private Build getBuild() {
		return buildModel.getObject();
	}
	
	@Override
	protected void onDetach() {
		buildModel.detach();
		super.onDetach();
	}

	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canAccessBuild(getBuild());
	}
	
	@Override
	protected BookmarkablePageLink<Void> navToProject(String componentId, Project project) {
		if (project.isCodeManagement()) 
			return new ViewStateAwarePageLink<Void>(componentId, ProjectBuildsPage.class, ProjectBuildsPage.paramsOf(project, 0));
		else
			return new ViewStateAwarePageLink<Void>(componentId, ProjectDashboardPage.class, ProjectDashboardPage.paramsOf(project.getId()));
	}
	
	@Override
	protected Component newProjectTitle(String componentId) {
		Fragment fragment = new Fragment(componentId, "projectTitleFrag", this);
		fragment.add(new BookmarkablePageLink<Void>("builds", ProjectBuildsPage.class, 
				ProjectBuildsPage.paramsOf(getProject())));
		fragment.add(new Label("buildNumber", "#" + getBuild().getNumber()));
		return fragment;
	}

}
