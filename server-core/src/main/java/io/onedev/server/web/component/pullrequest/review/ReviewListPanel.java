package io.onedev.server.web.component.pullrequest.review;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;

import com.google.common.collect.Sets;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.entitymanager.PullRequestReviewManager;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestReview;
import io.onedev.server.model.PullRequestReview.Status;
import io.onedev.server.model.User;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.ajaxlistener.ConfirmClickListener;
import io.onedev.server.web.behavior.ChangeObserver;
import io.onedev.server.web.component.user.ident.Mode;
import io.onedev.server.web.component.user.ident.UserIdentPanel;
import io.onedev.server.web.page.base.BasePage;

public abstract class ReviewListPanel extends Panel {

	private final IModel<List<PullRequestReview>> reviewsModel;
	
	public ReviewListPanel(String id) {
		super(id);
		
		reviewsModel = new LoadableDetachableModel<List<PullRequestReview>>() {

			@Override
			protected List<PullRequestReview> load() {
				return getPullRequest().getSortedReviews().stream()
						.filter(it-> it.getStatus() != Status.EXCLUDED)
						.collect(Collectors.toList());
			}
			
		};		
	}

	protected abstract PullRequest getPullRequest();
	
	@Override
	protected void onDetach() {
		reviewsModel.detach();
		super.onDetach();
	}

	@Override
	protected void onConfigure() {
		super.onConfigure();
		
		PullRequest request = getPullRequest();
		setVisible(!reviewsModel.getObject().isEmpty() || SecurityUtils.canModifyPullRequest(request) && request.isOpen());
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new ListView<>("reviews", reviewsModel) {

			@Override
			protected void populateItem(ListItem<PullRequestReview> item) {
				PullRequestReview review = item.getModelObject();
				item.add(new UserIdentPanel("user", review.getUser(), Mode.AVATAR_AND_NAME));

				PullRequest request = getPullRequest();

				item.add(new ReviewStatusIcon("status", false) {

					@Override
					protected Status getStatus() {
						return item.getModelObject().getStatus();
					}

				}.setVisible(!request.isNew()));

				item.add(new AjaxLink<Void>("refresh") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);

						PullRequestReview review = item.getModelObject();
						if (!review.getUser().equals(SecurityUtils.getAuthUser())) {
							attributes.getAjaxCallListeners().add(new ConfirmClickListener("Do you really want to "
									+ "request another review from '" + review.getUser().getDisplayName() + "'?"));
						}
					}

					@Override
					protected void onComponentTag(ComponentTag tag) {
						super.onComponentTag(tag);
						if (item.getModelObject().getUser().equals(SecurityUtils.getAuthUser()))
							tag.put("title", "Reset my review");
					}

					@Override
					public void onClick(AjaxRequestTarget target) {
						PullRequestReview review = item.getModelObject();
						review.setStatus(Status.PENDING);
						OneDev.getInstance(PullRequestReviewManager.class).createOrUpdate(review);
						notifyPullRequestChange(target);
					}

					@Override
					protected void onConfigure() {
						super.onConfigure();

						PullRequestReview review = item.getModelObject();
						User currentUser = SecurityUtils.getAuthUser();
						setVisible(!request.isNew()
								&& request.isOpen()
								&& review.getStatus() != PullRequestReview.Status.PENDING
								&& (SecurityUtils.canModifyPullRequest(getPullRequest()) || review.getUser().equals(currentUser)));
					}

				});

				item.add(new AjaxLink<Void>("delete") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);
						if (!getPullRequest().isNew()) {
							var review = item.getModelObject();
							attributes.getAjaxCallListeners().add(new ConfirmClickListener("Do you really want to "
									+ "remove reviewer '" + review.getUser().getDisplayName() + "'?"));
						}
					}

					@Override
					public void onClick(AjaxRequestTarget target) {
						PullRequest request = getPullRequest();
						PullRequestReview review = item.getModelObject();
						review.setStatus(Status.EXCLUDED);
						OneDev.getInstance(PullRequestManager.class).checkReviews(request, false);
						User reviewer = review.getUser();
						boolean reviewerRequired = false;
						if (request.isNew()) {
							if (request.getReview(reviewer).getStatus() != Status.EXCLUDED)
								reviewerRequired = true;
						} else if (request.getReview(reviewer).getStatus() == Status.EXCLUDED) {
							getReviewManager().createOrUpdate(review);
							for (PullRequestReview eachReview : request.getReviews()) {
								if (eachReview.isNew())
									getReviewManager().createOrUpdate(eachReview);
							}
							notifyPullRequestChange(target);
						} else {
							reviewerRequired = true;
						}
						if (reviewerRequired) {
							getSession().warn("Reviewer '" + reviewer.getDisplayName()
									+ "' is required and can not be removed");
						} else if (request.isNew()) {
							target.add(ReviewListPanel.this);
						}

						reviewsModel.detach();
					}

					@Override
					protected void onConfigure() {
						super.onConfigure();
						setVisible(SecurityUtils.canModifyPullRequest(getPullRequest()) && request.isOpen());
					}

				});
			}

		});
		
		add(new ReviewerChoice("addReviewer") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen() && SecurityUtils.canModifyPullRequest(getPullRequest()));
			}

			@Override
			protected void onSelect(AjaxRequestTarget target, User user) {
				super.onSelect(target, user);
				target.add(ReviewListPanel.this);
			}

			@Override
			protected PullRequest getPullRequest() {
				return ReviewListPanel.this.getPullRequest();
			}
		                                                                                                                              
		});
		
		add(new ChangeObserver() {
			
			@Override
			public Collection<String> findObservables() {
				return Sets.newHashSet(PullRequest.getChangeObservable(getPullRequest().getId()));
			}
			
		});
		
		setOutputMarkupId(true);
	}

	private PullRequestReviewManager getReviewManager() {
		return OneDev.getInstance(PullRequestReviewManager.class);
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new ReviewCssResourceReference()));
	}

	private void notifyPullRequestChange(AjaxRequestTarget target) {
		((BasePage)getPage()).notifyObservableChange(target,
				PullRequest.getChangeObservable(getPullRequest().getId()));
	}	
}
