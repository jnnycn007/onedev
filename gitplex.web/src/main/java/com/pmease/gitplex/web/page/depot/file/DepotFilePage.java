package com.pmease.gitplex.web.page.depot.file;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.wicket.Component;
import org.apache.wicket.MetaDataKey;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.protocol.ws.api.WebSocketRequestHandler;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.PackageResourceReference;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.google.common.base.Objects;
import com.pmease.commons.git.Blob;
import com.pmease.commons.git.BlobIdent;
import com.pmease.commons.git.GitUtils;
import com.pmease.commons.git.exception.GitObjectNotFoundException;
import com.pmease.commons.hibernate.UnitOfWork;
import com.pmease.commons.lang.diff.DiffUtils;
import com.pmease.commons.lang.extractors.TokenPosition;
import com.pmease.commons.loader.ListenerRegistry;
import com.pmease.commons.wicket.component.modal.ModalLink;
import com.pmease.commons.wicket.websocket.WebSocketManager;
import com.pmease.commons.wicket.websocket.WebSocketRegion;
import com.pmease.commons.wicket.websocket.WebSocketRenderBehavior;
import com.pmease.gitplex.core.GitPlex;
import com.pmease.gitplex.core.entity.CodeComment;
import com.pmease.gitplex.core.entity.Depot;
import com.pmease.gitplex.core.entity.PullRequest;
import com.pmease.gitplex.core.entity.PullRequestUpdate;
import com.pmease.gitplex.core.entity.support.CommentPos;
import com.pmease.gitplex.core.entity.support.TextRange;
import com.pmease.gitplex.core.event.RefUpdated;
import com.pmease.gitplex.core.manager.CodeCommentManager;
import com.pmease.gitplex.core.manager.DepotManager;
import com.pmease.gitplex.core.manager.PullRequestManager;
import com.pmease.gitplex.core.security.SecurityUtils;
import com.pmease.gitplex.search.IndexManager;
import com.pmease.gitplex.search.SearchManager;
import com.pmease.gitplex.search.hit.QueryHit;
import com.pmease.gitplex.search.query.BlobQuery;
import com.pmease.gitplex.search.query.TextQuery;
import com.pmease.gitplex.web.WebSession;
import com.pmease.gitplex.web.component.archivemenulink.ArchiveMenuLink;
import com.pmease.gitplex.web.component.depotfile.blobsearch.advanced.AdvancedSearchPanel;
import com.pmease.gitplex.web.component.depotfile.blobsearch.instant.InstantSearchPanel;
import com.pmease.gitplex.web.component.depotfile.blobsearch.result.SearchResultPanel;
import com.pmease.gitplex.web.component.depotfile.blobview.BlobNameChangeCallback;
import com.pmease.gitplex.web.component.depotfile.blobview.BlobRenderer;
import com.pmease.gitplex.web.component.depotfile.blobview.BlobViewContext;
import com.pmease.gitplex.web.component.depotfile.blobview.BlobViewPanel;
import com.pmease.gitplex.web.component.depotfile.blobview.source.SourceViewPanel;
import com.pmease.gitplex.web.component.depotfile.editsave.CancelListener;
import com.pmease.gitplex.web.component.depotfile.editsave.EditSavePanel;
import com.pmease.gitplex.web.component.depotfile.fileedit.FileEditPanel;
import com.pmease.gitplex.web.component.depotfile.filelist.FileListPanel;
import com.pmease.gitplex.web.component.depotfile.filenavigator.FileNavigator;
import com.pmease.gitplex.web.component.revisionpicker.RevisionPicker;
import com.pmease.gitplex.web.page.depot.DepotPage;
import com.pmease.gitplex.web.page.depot.NoBranchesPage;
import com.pmease.gitplex.web.page.depot.commit.DepotCommitsPage;
import com.pmease.gitplex.web.page.depot.pullrequest.requestdetail.changes.RequestChangesPage;
import com.pmease.gitplex.web.websocket.CodeCommentChangedRegion;
import com.pmease.gitplex.web.websocket.CommitIndexedRegion;
import com.pmease.gitplex.web.websocket.PullRequestChangedRegion;

@SuppressWarnings("serial")
public class DepotFilePage extends DepotPage implements BlobViewContext {

	private static class SearchResultKey extends MetaDataKey<ArrayList<QueryHit>> {
	};

	public static final SearchResultKey SEARCH_RESULT_KEY = new SearchResultKey();		
	
	private static class ViewStateKey extends MetaDataKey<String> {
	};
	
	public static final ViewStateKey VIEW_STATE_KEY = new ViewStateKey();	
	
	private static final String PARAM_REVISION = "revision";
	
	private static final String PARAM_PATH = "path";
	
	private static final String PARAM_REQUEST_COMPARE_INFO = "request_compare_info";
	
	private static final String PARAM_COMMENT = "comment";
	
	private static final String PARAM_MODE = "mode";
	
	private static final String PARAM_QUERY = "query";
	
	private static final String PARAM_VIEW_STATE = "view_state";
	
	private static final String PARAM_MARK = "mark";
	
	private static final String PARAM_ANCHOR = "anchor";
	
	private static final String REVISION_PICKER_ID = "revisionPicker";
	
	private static final String FILE_NAVIGATOR_ID = "fileNavigator";

	private static final String FILE_VIEWER_ID = "fileViewer";
	
	private static final String SEARCH_RESULD_ID = "searchResult";

	private BlobIdent blobIdent = new BlobIdent();
	
	private ObjectId resolvedRevision;
	
	private TextRange mark;
	
	private String anchor;
	
	private Mode mode;
	
	private RequestCompareInfo requestCompareInfo;
	
	private Long commentId;
	
	private Component revisionIndexing;
	
	private WebMarkupContainer searchResultContainer;
	
	private AtomicReference<String> newPathRef;	
	
	private ObjectId commitId;
	
	private transient List<QueryHit> queryHits;
	
	public DepotFilePage(final PageParameters params) {
		super(params);
		
		if (getDepot().getDefaultBranch() == null) 
			throw new RestartResponseException(NoBranchesPage.class, paramsOf(getDepot()));
		
		blobIdent.revision = params.get(PARAM_REVISION).toString();
		blobIdent.path = GitUtils.normalizePath(params.get(PARAM_PATH).toString());
		
		blobIdent.revision = GitUtils.normalizePath(params.get(PARAM_REVISION).toString());
		if (blobIdent.revision == null)
			blobIdent.revision = getDepot().getDefaultBranch();

		resolvedRevision = getDepot().getObjectId(blobIdent.revision);
		
		RevCommit commit = getDepot().getRevCommit(blobIdent.revision);
		commitId = commit.copy();
		
		if (blobIdent.path != null) {
			try (RevWalk revWalk = new RevWalk(getDepot().getRepository())) {
				RevTree revTree = commit.getTree();
				TreeWalk treeWalk = TreeWalk.forPath(getDepot().getRepository(), blobIdent.path, revTree);
				if (treeWalk == null) {
					throw new GitObjectNotFoundException("Unable to find blob path '" + blobIdent.path
							+ "' in revision '" + blobIdent.revision + "'");
				}
				blobIdent.mode = treeWalk.getRawMode(0);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			blobIdent.mode = FileMode.TREE.getBits();
		}
		
		String modeStr = params.get(PARAM_MODE).toString();
		if (modeStr != null)
			mode = Mode.valueOf(modeStr.toUpperCase());
		mark = TextRange.of(params.get(PARAM_MARK).toString());
		anchor = params.get(PARAM_ANCHOR).toString();
		
		commentId = params.get(PARAM_COMMENT).toOptionalLong();
		
		requestCompareInfo = RequestCompareInfo.fromString(params.get(PARAM_REQUEST_COMPARE_INFO).toString());
		
		queryHits = WebSession.get().getMetaData(SEARCH_RESULT_KEY);
		if (queryHits != null) { 
			WebSession.get().setMetaData(SEARCH_RESULT_KEY, null);
		} else {
			String symbol = params.get(PARAM_QUERY).toString();
			if (symbol != null) {
				BlobQuery query = new TextQuery(symbol, false, true, true, 
						null, null, SearchResultPanel.MAX_QUERY_ENTRIES);
				try {
					SearchManager searchManager = GitPlex.getInstance(SearchManager.class);
					queryHits = searchManager.search(depotModel.getObject(), commitId, query);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}								
			}
		}
		
		if (mode == Mode.EDIT || mode == Mode.DELETE) {
			if (!isOnBranch()) 
				throw new RuntimeException("Files can only be edited on branch");
			
			String path = blobIdent.path;
			if (path != null && blobIdent.isTree())
				path += "/";
			if (!SecurityUtils.canModify(getDepot(), blobIdent.revision, path))
				unauthorized();
		}
		
		String viewStateStr = params.get(PARAM_VIEW_STATE).toString();
		if (viewStateStr == null) {
			CodeComment comment = getOpenComment();
			if (comment != null) {
				int cursorLine;
				CommentPos commentPos = comment.getCommentPos();
				try {
					if (commentPos.getCommit().equals(resolvedRevision.name())) {
						cursorLine = commentPos.getRange().getBeginLine();
					} else {
						BlobIdent commentBlobIdent = new BlobIdent(
								commentPos.getCommit(), commentPos.getPath(), FileMode.TYPE_FILE);
						Blob blob = getDepot().getBlob(commentBlobIdent);
						List<String> oldLines = new ArrayList<>();
						for (String line: blob.getText().getLines()) {
							oldLines.add(comment.getCompareContext().getWhitespaceOption().process(line));
						}
						List<String> newLines = new ArrayList<>();
						for (String line: getDepot().getBlob(blobIdent).getText().getLines()) {
							newLines.add(comment.getCompareContext().getWhitespaceOption().process(line));
						}
						cursorLine = DiffUtils.getNewLineAround(oldLines, newLines, commentPos.getRange().getBeginLine());
					}
					viewStateStr = String.format("{\"cursor\":{\"line\":%d, \"ch\":0}}", cursorLine);
				} catch (GitObjectNotFoundException e) {
				}
			}
		}
		RequestCycle.get().setMetaData(VIEW_STATE_KEY, viewStateStr);		
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new InstantSearchPanel("instantSearch", depotModel, new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return blobIdent.revision;
			}
			
		}) {
			
			@Override
			protected void onSelect(AjaxRequestTarget target, QueryHit hit) {
				BlobIdent selected = new BlobIdent(blobIdent.revision, hit.getBlobPath(), 
						FileMode.REGULAR_FILE.getBits()); 
				DepotFilePage.this.onSelect(target, selected, hit.getTokenPos());
			}
			
			@Override
			protected void onMoreQueried(AjaxRequestTarget target, List<QueryHit> hits) {
				newSearchResult(target, hits);
				resizeWindow(target);
			}
			
		});
		
		add(new ModalLink("advancedSearch") {

			@Override
			protected Component newContent(String id) {
				return new AdvancedSearchPanel(id, depotModel, new AbstractReadOnlyModel<String>() {

					@Override
					public String getObject() {
						return blobIdent.revision;
					}
					
				}) {

					@Override
					protected void onSearchComplete(AjaxRequestTarget target, List<QueryHit> hits) {
						newSearchResult(target, hits);
						resizeWindow(target);
						close(target);
					}

					@Override
					protected void onCancel(AjaxRequestTarget target) {
						close(target);
					}

					@Override
					protected BlobIdent getCurrentBlob() {
						return blobIdent;
					}
					
				};
			}
			
		});

		add(new Link<Void>("history") {

			@Override
			public void onClick() {
				DepotCommitsPage.State state = new DepotCommitsPage.State();
				state.setCompareWith(resolvedRevision.name());
				if (blobIdent.path != null) 
					state.setQuery(String.format("path(%s)", blobIdent.path));
				setResponsePage(DepotCommitsPage.class, DepotCommitsPage.paramsOf(getDepot(), state));				
			}
			
		});
		
		add(new ArchiveMenuLink("download", depotModel) {

			@Override
			protected String getRevision() {
				return blobIdent.revision;
			}

		});
		
		newRevisionPicker(null);
		
		newFileNavigator(null);
		
		add(revisionIndexing = new WebMarkupContainer("revisionIndexing") {

			@Override
			protected void onInitialize() {
				super.onInitialize();
				add(new Image("icon", new PackageResourceReference(DepotFilePage.class, "indexing.gif")));
				setOutputMarkupPlaceholderTag(true);
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();

				IndexManager indexManager = GitPlex.getInstance(IndexManager.class);
				if (!indexManager.isIndexed(getDepot(), commitId)) {
					GitPlex.getInstance(IndexManager.class).indexAsync(getDepot(), commitId);
					setVisible(true);
				} else {
					setVisible(false);
				}
			}
			
		});

		newFileViewer(null);

		add(searchResultContainer = new WebMarkupContainer("searchResultContainer"));
		
		newSearchResult(null, queryHits);
		
		add(new WebSocketRenderBehavior() {
			
			@Override
			protected void onRender(WebSocketRequestHandler handler) {
				handler.add(revisionIndexing);
				resizeWindow(handler);
			}
			
		});
	}

	/*
	 * In case we are on a branch, this operation makes sure that the branch resolves
	 * to a certain commit during the life cycle of our page, unless the page is 
	 * refreshed. This can avoid the issue that displayed file content and subsequent 
	 * operations encounters different commit if someone commits to the branch while 
	 * we are staying on the page. 
	 */
	@Override
	protected Map<String, ObjectId> getObjectIdCache() {
		Map<String, ObjectId> objectIdCache = new HashMap<>();
		if (resolvedRevision != null)
			objectIdCache.put(blobIdent.revision, resolvedRevision);
		return objectIdCache;
	}

	@Override
	public CodeComment getOpenComment() {
		if (commentId != null)
			return GitPlex.getInstance(CodeCommentManager.class).load(commentId);
		else
			return null;
	}
	
	private void newFileNavigator(@Nullable AjaxRequestTarget target) {
		final BlobNameChangeCallback callback;

		if (mode == Mode.EDIT) {
			callback = new BlobNameChangeCallback() {

				@Override
				public void onChange(AjaxRequestTarget target, String blobName) {
					String newPath;
					if (blobIdent.isTree()) {
						if (blobIdent.path != null)
							newPath = blobIdent.path + "/" + blobName;
						else
							newPath = blobName;
					} else {
						if (blobIdent.path.contains("/"))
							newPath = StringUtils.substringBeforeLast(blobIdent.path, "/") + "/" + blobName;
						else
							newPath = blobName;
					}
					newPathRef.set(GitUtils.normalizePath(newPath));
					((FileEditPanel) get(FILE_VIEWER_ID)).onNewPathChange(target, newPathRef.get());
				}
				
			};
		} else {
			callback = null;
		}
		
		Component fileNavigator = new FileNavigator(FILE_NAVIGATOR_ID, this, callback) {

			@Override
			protected void onNewFile(AjaxRequestTarget target) {
				mode = Mode.EDIT;
				
				newFileNavigator(target);
				newFileViewer(target);
				
				pushState(target);
				resizeWindow(target);
			}
			
		};
		if (target != null) {
			replace(fileNavigator);
			target.add(fileNavigator);
		} else {
			add(fileNavigator);
		}
	}
	
	private BlobViewPanel renderBlobViewer(String panelId) {
		for (BlobRenderer renderer: GitPlex.getExtensions(BlobRenderer.class)) {
			BlobViewPanel panel = renderer.render(panelId, this);
			if (panel != null)
				return panel;
		}
				
		throw new IllegalStateException("No applicable blob renderer found.");
	}
	
	private void newFileViewer(@Nullable AjaxRequestTarget target) {
		Component fileViewer;
		if (mode == Mode.EDIT) {
			final String refName = GitUtils.branch2ref(blobIdent.revision);
			newPathRef = new AtomicReference<>(blobIdent.isTree()?null:blobIdent.path);			
			fileViewer = new FileEditPanel(
					FILE_VIEWER_ID, depotModel, refName, 
					blobIdent.isTree()?null:blobIdent.path, 
					blobIdent.isTree()?"":getDepot().getBlob(blobIdent).getText().getContent(), 
							getDepot().getObjectId(blobIdent.revision), mark) {
 
				@Override
				protected void onCommitted(AjaxRequestTarget target, ObjectId oldCommit, ObjectId newCommit) {
					Depot depot = getDepot();
					String branch = blobIdent.revision;
					depot.cacheObjectId(branch, newCommit);
					resolvedRevision = newCommit;
					BlobIdent committed = new BlobIdent(
							branch, newPathRef.get(), FileMode.REGULAR_FILE.getBits());
					
					Subject subject = SecurityUtils.getSubject();
					
					Long depotId = depot.getId();
					ObjectId oldCommitId = oldCommit.copy();
					ObjectId newCommitId = newCommit.copy();
					GitPlex.getInstance(UnitOfWork.class).doAsync(new Runnable() {

						@Override
						public void run() {
							ThreadContext.bind(subject);
							try {
								Depot depot = GitPlex.getInstance(DepotManager.class).load(depotId);
								depot.cacheObjectId(branch, oldCommitId);
								RefUpdated event = new RefUpdated(depot, refName, oldCommitId, newCommitId);
								GitPlex.getInstance(ListenerRegistry.class).post(event);
							} finally {
								ThreadContext.unbindSubject();
							}
						}
						
					});

					if (requestCompareInfo != null) {
						showRequestChanges(target, oldCommitId, newCommitId);
					} else {
			    		State state = getState();
		    			state.blobIdent = committed;
		    			state.mode = null;
		    			applyState(target, state);
		    			pushState(target);
		    			resizeWindow(target);
					}
					// fix the issue that sometimes indexing indicator of new commit does not disappear 
	    			target.appendJavaScript("Wicket.WebSocket.send('RenderCallback');");	    			
				}

				@Override
				protected void onCancel(AjaxRequestTarget target) {
					if (requestCompareInfo != null) {
						PullRequest request = GitPlex.getInstance(PullRequestManager.class).load(requestCompareInfo.requestId);
						PageParameters params = RequestChangesPage.paramsOf(request, requestCompareInfo.compareState);
						setResponsePage(RequestChangesPage.class, params);
					} else {
						mode = null;
						newFileNavigator(target);
						newFileViewer(target);
						pushState(target);
						resizeWindow(target);
					}
				}
				
			};
		} else if (mode == Mode.DELETE) {
			final String refName = GitUtils.branch2ref(blobIdent.revision);

			CancelListener cancelListener = new CancelListener() {

				@Override
				public void onCancel(AjaxRequestTarget target) {
					if (requestCompareInfo != null) {
						PullRequest request = GitPlex.getInstance(PullRequestManager.class).load(requestCompareInfo.requestId);
						PageParameters params = RequestChangesPage.paramsOf(request, requestCompareInfo.compareState);
						setResponsePage(RequestChangesPage.class, params);
					} else {
						mode = null;
						newFileViewer(target);
						
						pushState(target);
						resizeWindow(target);
					}
				}
				
			};
			
			fileViewer = new EditSavePanel(FILE_VIEWER_ID, depotModel, refName, blobIdent.path, 
					null, getDepot().getObjectId(blobIdent.revision), cancelListener) {

				@Override
				protected void onCommitted(AjaxRequestTarget target, ObjectId oldCommit, ObjectId newCommit) {
					Depot depot = getDepot();
					String branch = blobIdent.revision;
					depot.cacheObjectId(branch, newCommit);
					resolvedRevision = newCommit;
					try (RevWalk revWalk = new RevWalk(getDepot().getRepository())) {
						RevTree revTree = getDepot().getRevCommit(newCommit).getTree();
						String parentPath = StringUtils.substringBeforeLast(blobIdent.path, "/");
						while (TreeWalk.forPath(getDepot().getRepository(), parentPath, revTree) == null) {
							if (parentPath.contains("/")) {
								parentPath = StringUtils.substringBeforeLast(parentPath, "/");
							} else {
								parentPath = null;
								break;
							}
						}

						Long depotId = depot.getId();
						Subject subject = SecurityUtils.getSubject();
						ObjectId oldCommitId = oldCommit.copy();
						ObjectId newCommitId = newCommit.copy();
						GitPlex.getInstance(UnitOfWork.class).doAsync(new Runnable() {

							@Override
							public void run() {
								ThreadContext.bind(subject);
								try {
									Depot depot = GitPlex.getInstance(DepotManager.class).load(depotId);
									depot.cacheObjectId(branch, newCommitId);
									RefUpdated event = new RefUpdated(depot, refName, oldCommitId, newCommitId);
									GitPlex.getInstance(ListenerRegistry.class).post(event);
								} finally {
									ThreadContext.unbindSubject();
								}
							}
							
						});
						
						if (requestCompareInfo != null) {
							showRequestChanges(target, oldCommitId, newCommitId);
						} else {
							BlobIdent parentBlobIdent = new BlobIdent(branch, parentPath, FileMode.TREE.getBits());
							State state = getState();
							state.blobIdent = parentBlobIdent;
							state.mode = null;
							applyState(target, state);
							pushState(target);
							resizeWindow(target);
						}
						// fix the issue that sometimes indexing indicator of new commit does not disappear 
		    			target.appendJavaScript("Wicket.WebSocket.send('RenderCallback');");	    			
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				
			};
		} else if (blobIdent.path == null || blobIdent.isTree()) {
			fileViewer = new FileListPanel(FILE_VIEWER_ID, depotModel, blobIdent) {

				@Override
				protected void onSelect(AjaxRequestTarget target, BlobIdent blobIdent) {
					DepotFilePage.this.onSelect(target, blobIdent, null);
				}
				
			};
		} else {
			fileViewer = renderBlobViewer(FILE_VIEWER_ID);
		}
		if (target != null) {
			replace(fileViewer);
			target.add(fileViewer);
		} else {
			add(fileViewer);
		}
	}
	
	private void showRequestChanges(AjaxRequestTarget target, ObjectId oldCommitId, ObjectId newCommitId) {
		RequestChangesPage.State state = SerializationUtils.clone(requestCompareInfo.compareState);
		state.newCommit = newCommitId.name();
		state.pathFilter = null;
		if (state.commentId != null) {
			CodeComment comment = GitPlex.getInstance(CodeCommentManager.class).load(state.commentId);
			state.oldCommit = comment.getCommentPos().getCommit();
		} else {
			state.oldCommit = oldCommitId.name();
		}
		get(FILE_VIEWER_ID).add(new WebSocketRenderBehavior() {
			
			@Override
			protected void onRender(WebSocketRequestHandler handler) {
				PullRequest request = GitPlex.getInstance(PullRequestManager.class).load(requestCompareInfo.requestId);
				for (PullRequestUpdate update: request.getUpdates()) {
					if (update.getHeadCommitHash().equals(newCommitId.name())) {
						setResponsePage(RequestChangesPage.class, RequestChangesPage.paramsOf(request, state));
						break;
					}
				}
			}
			
		});
	}
	
	private void pushState(AjaxRequestTarget target) {
		PageParameters params = paramsOf(getDepot(), getState());
		CharSequence url = RequestCycle.get().urlFor(DepotFilePage.class, params);
		pushState(target, url.toString(), getState());
	}
	
	private State getState() {
		State state = new State();
		state.blobIdent = new BlobIdent(blobIdent);
		state.mark = mark;
		state.mode = mode;
		state.anchor = anchor;
		state.commentId = commentId;
		state.requestCompareInfo = requestCompareInfo;
		return state;
	}
	
	private void setState(State state) {
		if (!blobIdent.revision.equals(state.blobIdent.revision)) {
			blobIdent = new BlobIdent(state.blobIdent);
			/* 
			 * a hack to reset resolved revision to null to disable getObjectIdCache()
			 * temporarily as otherwise getObjectId() method below will always 
			 * resolved to existing value of resolvedRevision
			 */
			resolvedRevision = null;
			resolvedRevision = getDepot().getObjectId(blobIdent.revision);
		} else {
			blobIdent = new BlobIdent(state.blobIdent);
		}
		mark = state.mark;
		anchor = state.anchor;
		mode = state.mode;
		commentId = state.commentId;
		requestCompareInfo = state.requestCompareInfo;
		GitPlex.getInstance(WebSocketManager.class).onRegionChange(this);
	}
	
	private void onSelect(AjaxRequestTarget target, String revision) {
		State state = getState();
		state.blobIdent.revision = revision;
		state.requestCompareInfo = null;
		state.commentId = null;
		state.mode = null;
		state.mark = null;

		if (state.blobIdent.path != null) {
			try (RevWalk revWalk = new RevWalk(getDepot().getRepository())) {
				RevTree revTree = getDepot().getRevCommit(revision, true).getTree();
				TreeWalk treeWalk = TreeWalk.forPath(getDepot().getRepository(), blobIdent.path, revTree);
				if (treeWalk != null) {
					state.blobIdent.mode = treeWalk.getRawMode(0);
				} else {
					state.blobIdent.path = null;
					state.blobIdent.mode = FileMode.TREE.getBits();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		applyState(target, state);
		pushState(target);
		resizeWindow(target);
	}
	
	private void newRevisionPicker(@Nullable AjaxRequestTarget target) {
		Component revisionPicker = new RevisionPicker(REVISION_PICKER_ID, depotModel, blobIdent.revision, true) {

			@Override
			protected String getRevisionUrl(String revision) {
				State state = new State();
				state.blobIdent.revision = revision;
				PageParameters params = DepotFilePage.paramsOf(depotModel.getObject(), state);
				return urlFor(DepotFilePage.class, params).toString();
			}

			@Override
			protected void onSelect(AjaxRequestTarget target, String revision) {
				DepotFilePage.this.onSelect(target, revision);
			}
			
		}; 
		if (target != null) {
			replace(revisionPicker);
			target.add(revisionPicker);
		} else {
			add(revisionPicker);
		}
	}
	
	private void applyState(AjaxRequestTarget target, State state) {
		if (!state.blobIdent.revision.equals(blobIdent.revision))
			newSearchResult(target, null);
		
		setState(state);
		commitId = getDepot().getRevCommit(blobIdent.revision).copy();
		GitPlex.getInstance(WebSocketManager.class).onRegionChange(this);

		newRevisionPicker(target);
		
		newFileNavigator(target);
		
		target.add(revisionIndexing);
		
		newFileViewer(target);
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);

		response.render(JavaScriptHeaderItem.forReference(new DepotFileResourceReference()));
	}

	public static PageParameters paramsOf(Depot depot, CodeComment comment, @Nullable String anchor) {
		DepotFilePage.State state = new DepotFilePage.State();
		state.blobIdent.revision = comment.getCommentPos().getCommit();
		state.blobIdent.path = comment.getCommentPos().getPath();
		state.blobIdent.mode = FileMode.TYPE_FILE;
		state.commentId = comment.getId();
		state.mark = comment.getCommentPos().getRange();
		state.anchor = anchor;
		return paramsOf(depot, state);
	}
	
	public static PageParameters paramsOf(Depot depot, State state) {
		PageParameters params = paramsOf(depot);
		if (state.blobIdent.revision != null)
			params.set(PARAM_REVISION, state.blobIdent.revision);
		if (state.blobIdent.path != null)
			params.set(PARAM_PATH, state.blobIdent.path);
		if (state.mark != null)
			params.set(PARAM_MARK, state.mark.toString());
		if (state.anchor != null)
			params.set(PARAM_ANCHOR, state.anchor);
		if (state.requestCompareInfo != null)
			params.set(PARAM_REQUEST_COMPARE_INFO, state.requestCompareInfo.toString());
		if (state.commentId != null)
			params.set(PARAM_COMMENT, state.commentId);
		if (state.mode != null)
			params.set(PARAM_MODE, state.mode.name().toLowerCase());
		if (state.query != null)
			params.set(PARAM_QUERY, state.query);
		if (state.viewState != null)
			params.set(PARAM_VIEW_STATE, state.viewState);
		return params;
	}
	
	private void newSearchResult(@Nullable AjaxRequestTarget target, @Nullable List<QueryHit> hits) {
		Component searchResult;
		if (hits != null) {
			searchResult = new SearchResultPanel(SEARCH_RESULD_ID, this, hits) {
				
				@Override
				protected void onClose(AjaxRequestTarget target) {
					newSearchResult(target, null);
					resizeWindow(target);
				}
				
			};
			if (target != null) {
				target.appendJavaScript(""
						+ "$('#repo-file>.search-result').show(); "
						+ "$('#repo-file .search-result>.body').focus();");
			}
		} else {
			searchResult = new WebMarkupContainer(SEARCH_RESULD_ID).setOutputMarkupId(true);
			if (target != null) 
				target.appendJavaScript("$('#repo-file>.search-result').hide();");
			else 
				searchResultContainer.add(AttributeAppender.replace("style", "display: none;"));
		}
		if (target != null) {
			searchResultContainer.replace(searchResult);
			target.add(searchResult);
		} else {
			searchResultContainer.add(searchResult);
		}
	}
	
	@Override
	protected void onPopState(AjaxRequestTarget target, Serializable data) {
		super.onPopState(target, data);
		
		applyState(target, (State) data);
		resizeWindow(target);
	}
	
	private void resizeWindow(IPartialPageRequestHandler partialPageRequestHandler) {
		partialPageRequestHandler.appendJavaScript("$(window).resize();");
	}
	
	@Override
	protected void onSelect(AjaxRequestTarget target, Depot depot) {
		setResponsePage(DepotFilePage.class, paramsOf(depot));
	}
	
	@Override
	public BlobIdent getBlobIdent() {
		return blobIdent;
	}

	@Override
	public TextRange getMark() {
		return mark;
	}

	@Override
	public String getAnchor() {
		return anchor;
	}
	
	@Override
	public void onMark(AjaxRequestTarget target, TextRange mark) {
		this.mark = mark;
		pushState(target);
	}

	@Override
	public String getMarkUrl(TextRange mark) {
		State state = getState();
		state.blobIdent.revision = resolvedRevision.name();
		state.requestCompareInfo = null;
		state.commentId = null;
		state.mark = mark;
		PageParameters params = paramsOf(getDepot(), state);		
		return RequestCycle.get().urlFor(DepotFilePage.class, params).toString();
	}
	
	@Override
	public Mode getMode() {
		return mode;
	}

	@Override
	public void onSelect(AjaxRequestTarget target, BlobIdent blobIdent, @Nullable TokenPosition tokenPos) {
		mark = TextRange.of(tokenPos);
		if (Objects.equal(DepotFilePage.this.blobIdent.path, blobIdent.path)) {
			if (mark != null) {
				Component fileViewer = get(FILE_VIEWER_ID);
				if (fileViewer instanceof SourceViewPanel) {
					SourceViewPanel sourceViewer = (SourceViewPanel) fileViewer;
					sourceViewer.mark(target, mark, true);
				} else if (fileViewer instanceof FileEditPanel) {
					FileEditPanel fileEditor = (FileEditPanel) fileViewer;
					fileEditor.mark(target, mark);
				} else {
					newFileViewer(target);
					resizeWindow(target);
				}
			}
		} else {
			DepotFilePage.this.blobIdent.path = blobIdent.path;
			DepotFilePage.this.blobIdent.mode = blobIdent.mode;
			
			mode = null;
			commentId = null;
			GitPlex.getInstance(WebSocketManager.class).onRegionChange(this);
			
			newFileNavigator(target);
			newFileViewer(target);
			
			resizeWindow(target);
		}
		pushState(target);
	}

	@Override
	public void onSearchComplete(AjaxRequestTarget target, List<QueryHit> hits) {
		newSearchResult(target, hits);
		resizeWindow(target);
	}

	@Override
	public void onBlameChange(AjaxRequestTarget target, boolean blame) {
		mode = (blame?Mode.BLAME:null);
		Component fileViewer = get(FILE_VIEWER_ID);
		if (!(fileViewer instanceof SourceViewPanel)) {
			newFileViewer(target);
			resizeWindow(target);
		}
		pushState(target);
	}

	@Override
	public void onDelete(AjaxRequestTarget target) {
		mode = Mode.DELETE;

		newFileViewer(target);
		pushState(target);
		resizeWindow(target);
	}

	@Override
	public void onEdit(AjaxRequestTarget target) {
		mode = Mode.EDIT;
		
		newFileNavigator(target);
		newFileViewer(target);
		pushState(target);
		resizeWindow(target);
	}

	@Override
	public void onCommentOpened(AjaxRequestTarget target, CodeComment comment) {
		if (comment != null) {
			commentId = comment.getId();
			mark = comment.getCommentPos().getRange();
		} else {
			commentId = null;
			mark = null;
		}
		GitPlex.getInstance(WebSocketManager.class).onRegionChange(this);
		pushState(target);
	}

	@Override
	public void onAddComment(AjaxRequestTarget target, TextRange mark) {
		commentId = null;
		GitPlex.getInstance(WebSocketManager.class).onRegionChange(this);
		this.mark = mark;
		pushState(target);
	}
	
	@Override
	public boolean isOnBranch() {
		return getDepot().getBranchRef(blobIdent.revision) != null;
	}

	@Override
	public RevCommit getCommit() {
		return getDepot().getRevCommit(getBlobIdent().revision);
	}
	
	@Override
	protected boolean isFootVisible() {
		return false;
	}

	@Override
	public Collection<WebSocketRegion> getWebSocketRegions() {
		List<WebSocketRegion> regions = new ArrayList<>();
		regions.add(new CommitIndexedRegion(getDepot().getId(), commitId));
		if (commentId != null)
			regions.add(new CodeCommentChangedRegion(commentId));
		if (requestCompareInfo != null)
			regions.add(new PullRequestChangedRegion(requestCompareInfo.requestId));
		return regions;
	}

	public static class State implements Serializable {
		
		private static final long serialVersionUID = 1L;

		public Long commentId;
		
		public BlobIdent blobIdent = new BlobIdent();
		
		public TextRange mark;
		
		public String anchor;
		
		public Mode mode;
		
		public RequestCompareInfo requestCompareInfo;
		
		public transient String query;
		
		public transient String viewState;		
	}

}
