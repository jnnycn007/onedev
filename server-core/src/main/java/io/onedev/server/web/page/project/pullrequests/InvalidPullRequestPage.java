package io.onedev.server.web.page.project.pullrequests;

import static io.onedev.server.web.translation.Translation._T;
import static java.util.stream.Collectors.toList;
import static org.unbescape.html.HtmlEscape.escapeHtml5;

import java.text.MessageFormat;

import javax.persistence.EntityNotFoundException;

import org.apache.wicket.Component;
import org.apache.wicket.Session;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.flow.RedirectToUrlException;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.eclipse.jgit.lib.ObjectId;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.WebSession;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.markdown.MarkdownViewer;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.dashboard.ProjectDashboardPage;
import io.onedev.server.web.util.ConfirmClickModifier;

public class InvalidPullRequestPage extends ProjectPage {

	public static final String PARAM_REQUEST = "request";
	
	private IModel<PullRequest> requestModel;
	
	public InvalidPullRequestPage(PageParameters params) {
		super(params);
		
		requestModel = new LoadableDetachableModel<PullRequest>() {

			@Override
			protected PullRequest load() {
				Long requestNumber = params.get(PARAM_REQUEST).toLong();
				PullRequest request = OneDev.getInstance(PullRequestManager.class).find(getProject(), requestNumber);
				if (request == null)
					throw new EntityNotFoundException("Unable to find pull request #" + requestNumber + " in project " + getProject());
				Preconditions.checkState(!request.isValid());
				return request;
			}

		};
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		var request = getPullRequest();
		add(new Label("missingCommits", StringUtils.join(request.getMissingCommits().stream().map(ObjectId::getName).collect(toList()), " ")));
		add(new Label("requestBranches", escapeHtml5(request.getTargetBranch()) + " &larr; " + escapeHtml5(request.getSourceBranch())).setEscapeModelStrings(false));
		if (request.getDescription() != null) {
			add(new Label("requestTitle", request.getTitle()).add(AttributeAppender.append("class", "mb-4")));
			add(new MarkdownViewer("requestDescription", Model.of(request.getDescription()), null));
		} else {
			add(new Label("requestTitle", request.getTitle()));
			add(new WebMarkupContainer("requestDescription").setVisible(false));
		}
		
		add(new Link<Void>("delete") {

			@Override
			public void onClick() {
				OneDev.getInstance(PullRequestManager.class).delete(getPullRequest());
				
				Session.get().success(MessageFormat.format(_T("Pull request #{0} deleted"), getPullRequest().getNumber()));
				
				String redirectUrlAfterDelete = WebSession.get().getRedirectUrlAfterDelete(PullRequest.class);
				if (redirectUrlAfterDelete != null)
					throw new RedirectToUrlException(redirectUrlAfterDelete);
				else
					setResponsePage(ProjectPullRequestsPage.class, ProjectPullRequestsPage.paramsOf(getProject()));
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(SecurityUtils.canManageProject(getPullRequest().getTargetProject()));
			}
			
		}.add(new ConfirmClickModifier(MessageFormat.format(_T("Do you really want to delete pull request #{0}?"), getPullRequest().getNumber()))));
	}

	public static PageParameters paramsOf(PullRequest request) {
		PageParameters params = ProjectPage.paramsOf(request.getTarget().getProject());
		params.add(PARAM_REQUEST, request.getNumber());
		return params;
	}
	
	private PullRequest getPullRequest() {
		return requestModel.getObject();
	}
	
	@Override
	protected void onDetach() {
		requestModel.detach();
		super.onDetach();
	}

	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject());
	}
	
	@Override
	protected Component newProjectTitle(String componentId) {
		Fragment fragment = new Fragment(componentId, "projectTitleFrag", this);
		fragment.add(new BookmarkablePageLink<Void>("pullRequests", ProjectPullRequestsPage.class, 
				ProjectPullRequestsPage.paramsOf(getProject())));
		fragment.add(new Label("pullRequestNumber", "#" + getPullRequest().getNumber()));
		return fragment;
	}

	@Override
	protected BookmarkablePageLink<Void> navToProject(String componentId, Project project) {
		if (project.isCodeManagement() && SecurityUtils.canReadCode(project)) 
			return new ViewStateAwarePageLink<Void>(componentId, ProjectPullRequestsPage.class, ProjectPullRequestsPage.paramsOf(project, 0));
		else
			return new ViewStateAwarePageLink<Void>(componentId, ProjectDashboardPage.class, ProjectDashboardPage.paramsOf(project.getId()));
	}
	
}
