package io.onedev.server.web.page.project.tags;

import com.google.common.base.Preconditions;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entityreference.LinkTransformer;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.service.GitService;
import io.onedev.server.git.service.RefFacade;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.User;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.Path;
import io.onedev.server.util.PathNode;
import io.onedev.server.web.WebConstants;
import io.onedev.server.web.ajaxlistener.ConfirmClickListener;
import io.onedev.server.web.asset.emoji.Emojis;
import io.onedev.server.web.behavior.OnTypingDoneBehavior;
import io.onedev.server.web.component.commit.status.CommitStatusLink;
import io.onedev.server.web.component.contributorpanel.ContributorPanel;
import io.onedev.server.web.component.datatable.DefaultDataTable;
import io.onedev.server.web.component.gitsignature.SignatureStatusPanel;
import io.onedev.server.web.component.link.ArchiveMenuLink;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.modal.ModalLink;
import io.onedev.server.web.component.modal.ModalPanel;
import io.onedev.server.web.component.user.ident.Mode;
import io.onedev.server.web.component.user.ident.PersonIdentPanel;
import io.onedev.server.web.editable.BeanContext;
import io.onedev.server.web.editable.BeanEditor;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.blob.ProjectBlobPage;
import io.onedev.server.web.page.project.commits.CommitDetailPage;
import io.onedev.server.web.page.project.dashboard.ProjectDashboardPage;
import io.onedev.server.web.util.LoadableDetachableDataProvider;
import io.onedev.server.web.util.paginghistory.PagingHistorySupport;
import io.onedev.server.web.util.paginghistory.ParamPagingHistorySupport;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.extensions.markup.html.repeater.data.grid.ICellPopulator;
import org.apache.wicket.extensions.markup.html.repeater.data.table.AbstractColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.DataTable;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.repeater.util.SortableDataProvider;
import org.apache.wicket.feedback.FencedFeedbackPanel;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.AbstractLink;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.protocol.http.WebSession;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.*;

import static io.onedev.server.entityreference.ReferenceUtils.transformReferences;
import static io.onedev.server.web.translation.Translation._T;

public class ProjectTagsPage extends ProjectPage {

	private static final String PARAM_PAGE = "page";
	
	private static final String PARAM_QUERY = "query";
	
	private final PagingHistorySupport pagingHistorySupport;	
	
	private TextField<String> searchField;
	
	private DataTable<RefFacade, Void> tagsTable;
	
	private String query;
	
	private boolean typing;
	
	private IModel<Map<String, RefFacade>> tagsModel = new LoadableDetachableModel<Map<String, RefFacade>>() {

		@Override
		protected Map<String, RefFacade> load() {
			Map<String, RefFacade> refInfos = new LinkedHashMap<>();
			for (RefFacade ref: getProject().getTagRefs()) {
				String tag = GitUtils.ref2tag(ref.getName());
				if (query == null || tag.toLowerCase().contains(query.trim().toLowerCase()))
					refInfos.put(tag, ref);
			}
			return refInfos;
		}
		
	};
	
	public ProjectTagsPage(PageParameters params) {
		super(params);
		
		query = params.get(PARAM_QUERY).toString();
		
		pagingHistorySupport = new ParamPagingHistorySupport() {
			
			@Override
			public PageParameters newPageParameters(int currentPage) {
				PageParameters params = paramsOf(getProject());
				params.add(PARAM_PAGE, currentPage+1);
				if (query != null)
					params.add(PARAM_QUERY, query);
				return params;
			}
			
			@Override
			public int getCurrentPage() {
				return getPageParameters().get(PARAM_PAGE).toInt(1)-1;
			}
			
		};
		
	}
	
	@Override
	protected void onPopState(AjaxRequestTarget target, Serializable data) {
		super.onPopState(target, data);
		query = (String) data;
		getPageParameters().set(PARAM_QUERY, query);
		target.add(searchField);
		target.add(tagsTable);
	}
	
	@Override
	protected void onBeforeRender() {
		typing = false;
		super.onBeforeRender();
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(searchField = new TextField<String>("filterTags", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return query;
			}

			@Override
			public void setObject(String object) {
				query = object;
				PageParameters params = getPageParameters();
				params.set(PARAM_QUERY, query);
				params.remove(PARAM_PAGE);
				
				String url = RequestCycle.get().urlFor(ProjectTagsPage.class, params).toString();

				AjaxRequestTarget target = RequestCycle.get().find(AjaxRequestTarget.class);
				if (typing)
					replaceState(target, url, query);
				else
					pushState(target, url, query);
				
				tagsTable.setCurrentPage(0);
				target.add(tagsTable);
				
				typing = true;
			}
			
		}));
		
		searchField.add(new OnTypingDoneBehavior(200) {

			@Override
			protected void onTypingDone(AjaxRequestTarget target) {
			}
			
		});
		
		add(new ModalLink("createTag") {

			private CreateTagBeanWithRevision helperBean = new CreateTagBeanWithRevision();
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(SecurityUtils.canCreateTag(getProject(), Constants.R_TAGS));
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				Fragment fragment = new Fragment(id, "createTagFrag", ProjectTagsPage.this);
				Form<?> form = new Form<Void>("form");
				form.setOutputMarkupId(true);
				form.add(new FencedFeedbackPanel("feedback", form));
				helperBean.setName(null);
				helperBean.setMessage(null);
				helperBean.setRevision(null);
				
				BeanEditor editor;
				form.add(editor = BeanContext.edit("editor", helperBean));
				
				form.add(new AjaxButton("create") {

					@Override
					protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
						super.onSubmit(target, form);
						
						String tagName = helperBean.getName();
						User user = Preconditions.checkNotNull(getLoginUser());
						
						if (getProject().getObjectId(GitUtils.tag2ref(tagName), false) != null) {
							editor.error(new Path(new PathNode.Named("name")), 
									MessageFormat.format(_T("Tag \"{0}\" already exists, please choose a different name"), tagName));
							target.add(form);
						} else if (getProject().getTagProtection(tagName, user).isPreventCreation()) {
							editor.error(new Path(new PathNode.Named("name")), _T("Unable to create protected tag")); 
							target.add(form);
						} else {
							try {
								OneDev.getInstance(GitService.class).createTag(getProject(), tagName, 
										helperBean.getRevision(), user.asPerson(), helperBean.getMessage(), 
										getProject().isTagSignatureRequired(user, tagName));
								modal.close();
								target.add(tagsTable);
								
								getSession().success(MessageFormat.format(_T("Tag \"{0}\" created"), tagName));
							} catch (Exception e) {
								ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
								if (explicitException != null) {
									editor.error(new Path(new PathNode.Named("name")), explicitException.getMessage());
									target.add(form);
								} else {
									throw ExceptionUtils.unchecked(e);
								}
							}
						}
					}

					@Override
					protected void onError(AjaxRequestTarget target, Form<?> form) {
						super.onError(target, form);
						target.add(form);
					}

				});
				form.add(new AjaxLink<Void>("cancel") {

					@Override
					public void onClick(AjaxRequestTarget target) {
						modal.close();
					}
					
				});
				form.add(new AjaxLink<Void>("close") {

					@Override
					public void onClick(AjaxRequestTarget target) {
						modal.close();
					}
					
				});
				fragment.add(form);
				return fragment;
			}
			
		});
		
		List<IColumn<RefFacade, Void>> columns = new ArrayList<>();
		
		columns.add(new AbstractColumn<RefFacade, Void>(Model.of("")) {

			@Override
			public String getCssClass() {
				return "tag";
			}

			@Override
			public void populateItem(Item<ICellPopulator<RefFacade>> cellItem, String componentId,
					IModel<RefFacade> rowModel) {
				Fragment fragment = new Fragment(componentId, "tagFrag", ProjectTagsPage.this);
				fragment.setRenderBodyOnly(true);
				
				RefFacade ref = rowModel.getObject();
				String tagName = GitUtils.ref2tag(ref.getName());
				
				BlobIdent blobIdent;
				if (getProject().getBranchRef(tagName) != null)
					blobIdent = new BlobIdent(ref.getName(), null, FileMode.TREE.getBits());
				else
					blobIdent = new BlobIdent(tagName, null, FileMode.TREE.getBits());
				ProjectBlobPage.State state = new ProjectBlobPage.State(blobIdent);
				AbstractLink link = new ViewStateAwarePageLink<Void>("tagLink", 
						ProjectBlobPage.class, ProjectBlobPage.paramsOf(getProject(), state));
				link.add(new Label("name", tagName));
				fragment.add(link);
				
				fragment.add(new CommitStatusLink("buildStatus", ref.getPeeledObj().copy(), ref.getName()) {

					@Override
					protected Project getProject() {
						return ProjectTagsPage.this.getProject();
					}

					@Override
					protected PullRequest getPullRequest() {
						return null;
					}
					
				});

				if (ref.getObj() instanceof RevTag) {
					fragment.add(new SignatureStatusPanel("signature") {

						@Override
						protected RevObject getRevObject() {
							return rowModel.getObject().getObj();
						}
						
					});
					
					RevTag revTag = (RevTag) ref.getObj();
					Fragment annotatedFragment = new Fragment("annotated", "annotatedFrag", ProjectTagsPage.this);
					if (revTag.getTaggerIdent() != null) 
						annotatedFragment.add(new PersonIdentPanel("author", revTag.getTaggerIdent(), "Tagger", Mode.NAME));
					else 
						annotatedFragment.add(new WebMarkupContainer("author").setVisible(false));
					Label message = new Label("message", Emojis.getInstance().apply(revTag.getFullMessage()));
					message.setOutputMarkupId(true);
					annotatedFragment.add(message);
					String toggleScript = String.format("$('#%s').toggle();", message.getMarkupId());
					WebMarkupContainer messageToggle = new WebMarkupContainer("messageToggle"); 
					messageToggle.add(AttributeAppender.append("onclick", toggleScript));
					messageToggle.setVisible(StringUtils.isNotBlank(revTag.getFullMessage()));
					annotatedFragment.add(messageToggle);
					
					fragment.add(annotatedFragment);
				} else {
					fragment.add(new WebMarkupContainer("signature").setVisible(false));
					fragment.add(new WebMarkupContainer("annotated").setVisible(false));
				}

				fragment.add(new Label("message", new LoadableDetachableModel<String>() {

					@Override
					protected String load() {
						var commit = (RevCommit) rowModel.getObject().getPeeledObj();
						var params = CommitDetailPage.paramsOf(getProject(), commit.name());
						var commitUrl = RequestCycle.get().urlFor(CommitDetailPage.class, params).toString();
						var transformed = transformReferences(commit.getShortMessage(), getProject(), 
								new LinkTransformer(commitUrl));
						return Emojis.getInstance().apply(transformed);
					}
					
				}).setEscapeModelStrings(false));
				
				RevCommit commit = (RevCommit) ref.getPeeledObj();
				
				fragment.add(new ContributorPanel("contributor", commit.getAuthorIdent(), commit.getCommitterIdent()));
				
				fragment.add(new ArchiveMenuLink("download", projectModel) {

					@Override
					protected String getRevision() {
						return ref.getName();
					}
					
				});
				
				fragment.add(new AjaxLink<Void>("delete") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);
						attributes.getAjaxCallListeners().add(new ConfirmClickListener(MessageFormat.format(_T("Do you really want to delete tag {0}?"), tagName)));
					}

					@Override
					protected void disableLink(ComponentTag tag) {
						super.disableLink(tag);
						tag.append("class", "disabled", " ");
						tag.put("data-tippy-content", _T("Deletion not allowed due to tag protection rule"));
					}

					@Override
					public void onClick(AjaxRequestTarget target) {
						OneDev.getInstance(ProjectManager.class).deleteTag(getProject(), tagName);
						WebSession.get().success(MessageFormat.format(_T("Tag \"{0}\" deleted"), tagName));
						target.add(tagsTable);
					}

					@Override
					protected void onConfigure() {
						super.onConfigure();

						Project project = getProject();
						if (SecurityUtils.canWriteCode(project)) 
							setEnabled(!project.getTagProtection(tagName, getLoginUser()).isPreventDeletion());
						else 
							setVisible(false);
					}
					
				});		
				
				cellItem.add(fragment);
			}
			
		});
		
		SortableDataProvider<RefFacade, Void> dataProvider = new LoadableDetachableDataProvider<RefFacade, Void>() {

			@Override
			public Iterator<? extends RefFacade> iterator(long first, long count) {
				List<RefFacade> tags = new ArrayList<>(tagsModel.getObject().values());
				if (first + count > tags.size())
					return tags.subList((int)first, tags.size()).iterator();
				else
					return tags.subList((int)first, (int)(first+count)).iterator();
			}

			@Override
			public long calcSize() {
				return tagsModel.getObject().size();
			}

			@Override
			public IModel<RefFacade> model(RefFacade object) {
				String tag = GitUtils.ref2tag(object.getName());
				return new AbstractReadOnlyModel<RefFacade>() {

					@Override
					public RefFacade getObject() {
						return tagsModel.getObject().get(tag);
					}
					
				};
			}
		};		
		
		add(tagsTable = new DefaultDataTable<RefFacade, Void>("tags", columns, dataProvider, 
				WebConstants.PAGE_SIZE, pagingHistorySupport) {
			
			@Override
			protected void onBeforeRender() {
				List<RefFacade> tags = new ArrayList<>(tagsModel.getObject().values());
				long firstItemOffset = tagsTable.getCurrentPage() * tagsTable.getItemsPerPage();
				List<ObjectId> commitIdsToDisplay = new ArrayList<>();
				for (long i=firstItemOffset; i<tags.size(); i++) {
					if (i-firstItemOffset >= tagsTable.getItemsPerPage())
						break;
					RefFacade ref = tags.get((int)i); 
					commitIdsToDisplay.add(ref.getObjectId());
				}
				
				BuildManager buildManager = OneDev.getInstance(BuildManager.class);
				getProject().cacheCommitStatuses(buildManager.queryStatus(getProject(), commitIdsToDisplay));
				super.onBeforeRender();
			}
			
		});		
	}
	
	@Override
	public void onDetach() {
		tagsModel.detach();
		super.onDetach();
	}
	
	@Override
	protected String getRobotsMeta() {
		return "noindex,nofollow";
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new ProjectTagsResourceReference()));
	}
	
	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject());
	}

	@Override
	protected Component newProjectTitle(String componentId) {
		return new Label(componentId, _T("Tags"));
	}

	@Override
	protected String getPageTitle() {
		return _T("Tags") + " - " + getProject().getPath();
	}
	
	@Override
	protected BookmarkablePageLink<Void> navToProject(String componentId, Project project) {
		if (project.isCodeManagement() && SecurityUtils.canReadCode(project)) 
			return new ViewStateAwarePageLink<Void>(componentId, ProjectTagsPage.class, ProjectTagsPage.paramsOf(project));
		else
			return new ViewStateAwarePageLink<Void>(componentId, ProjectDashboardPage.class, ProjectDashboardPage.paramsOf(project.getId()));
	}
	
}
