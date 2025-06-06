package io.onedev.server.web.component.markdown;

import static io.onedev.server.web.translation.Translation._T;
import static org.unbescape.html.HtmlEscape.escapeHtml5;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.activation.MimetypesFileTypeMap;
import javax.annotation.Nullable;

import org.apache.commons.fileupload.FileUploadException;
import org.apache.wicket.Component;
import org.apache.wicket.MetaDataKey;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.feedback.FencedFeedbackPanel;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.image.ExternalImage;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.protocol.http.WebSession;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.util.lang.Bytes;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.unbescape.javascript.JavaScriptEscape;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.attachment.AttachmentSupport;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.exception.ExceptionUtils;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.git.BlobIdentFilter;
import io.onedev.server.git.service.GitService;
import io.onedev.server.model.Project;
import io.onedev.server.util.FilenameUtils;
import io.onedev.server.util.UrlUtils;
import io.onedev.server.web.ajaxlistener.ConfirmClickListener;
import io.onedev.server.web.behavior.ReferenceInputBehavior;
import io.onedev.server.web.component.blob.BlobFolderSelector;
import io.onedev.server.web.component.blob.BlobSelector;
import io.onedev.server.web.component.dropzonefield.DropzoneField;
import io.onedev.server.web.component.floating.FloatingPanel;
import io.onedev.server.web.component.link.DropdownLink;
import io.onedev.server.web.component.tabbable.AjaxActionTab;
import io.onedev.server.web.component.tabbable.Tab;
import io.onedev.server.web.component.tabbable.Tabbable;
import io.onedev.server.web.page.project.blob.ProjectBlobPage;
import io.onedev.server.web.page.project.blob.render.BlobRenderContext;
import io.onedev.server.web.upload.FileUpload;
import io.onedev.server.web.upload.UploadManager;

abstract class InsertUrlPanel extends Panel {

	private static final MimetypesFileTypeMap MIME_TYPES = new MimetypesFileTypeMap();

	private static final MetaDataKey<String> ACTIVE_TAB = new MetaDataKey<String>(){};
	
	private static final MetaDataKey<String> UPLOAD_DIRECTORY = new MetaDataKey<String>(){};
	
	private static final MetaDataKey<HashSet<String>> FILE_PICKER_STATE = new MetaDataKey<HashSet<String>>(){};
	
	private static final MetaDataKey<HashSet<String>> FOLDER_PICKER_STATE = new MetaDataKey<HashSet<String>>(){};
				
	private static final String CONTENT_ID = "content";
	
	private String uploadId;
	
	private String linkUrl;
	
	private String linkText;
	
	private String commitMessage;
	
	private final MarkdownEditor markdownEditor;
	
	private final boolean isImage;
	
	public InsertUrlPanel(String id, MarkdownEditor markdownEditor, String linkText, boolean isImage) {
		super(id);
		this.markdownEditor = markdownEditor;
		this.isImage = isImage;
		if (StringUtils.isNotBlank(linkText))
			this.linkText = linkText;
	}

	private Component newInputUrlPanel() {
		Fragment fragment = new Fragment(CONTENT_ID, "inputUrlFrag", this) {

			@Override
			public void renderHead(IHeaderResponse response) {
				super.renderHead(response);
				String script = String.format("onedev.server.markdown.onInputUrlDomReady('%s');", getMarkupId());
				response.render(OnDomReadyHeaderItem.forScript(script));
			}
			
		};
		
		Form<?> form = new Form<Void>("form");
		form.add(new FencedFeedbackPanel("feedback", form));
		
		form.add(new Label("urlLabel", isImage? _T("Image URL") : _T("Link URL")));
		form.add(new Label("urlHelp", isImage? _T("Absolute or relative url of the image") : _T("Absolute or relative url of the link")));
		form.add(new TextField<String>("url", new PropertyModel<String>(this, "linkUrl")));
		
		form.add(new Label("textLabel", isImage? _T("Image Text") : _T("Link Text")));
		form.add(new TextField<String>("text", new PropertyModel<String>(this, "linkText")));
		
		form.add(new AjaxButton("insert", form) {

			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				super.onSubmit(target, form);
				if (StringUtils.isBlank(linkUrl)) {
					if (isImage)
						error(_T("Image URL should be specified"));
					else
						error(_T("Link URL should be specified"));
					target.add(fragment);
				} else {
					if (linkText == null)
						linkText = UrlUtils.describe(linkUrl);
					markdownEditor.insertUrl(target, isImage, linkUrl, linkText, null);
					onClose(target);
				}
			}
			
		});
		form.add(new AjaxLink<Void>("cancel") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				onClose(target);
			}
		});
		
		fragment.add(form);
		fragment.setOutputMarkupId(true);
		return fragment;
	}
	
	@Nullable
	private ObjectId resolveCommitId(BlobRenderContext context) {
		/*
		 * We resolve revision to get latest commit id so that we can select to insert newly 
		 * added/uploaded files while editing a markdown file
		 */
		String revision = context.getBlobIdent().revision;
		if (revision == null)
			revision = "main";
		GitService gitService = OneDev.getInstance(GitService.class);
		return gitService.resolve(context.getProject(), revision, false);
	}
	
	private Set<BlobIdent> getPickerState(@Nullable ObjectId commitId, BlobIdent currentBlobIdent, 
			@Nullable Set<String> expandedPaths) {
		Set<BlobIdent> pickerState = new HashSet<>();
		if (commitId != null) {
			if (expandedPaths != null) {
				for (String path: expandedPaths)
					pickerState.add(new BlobIdent(commitId.name(), path, FileMode.TREE.getBits()));
			} 
			
			String parentPath;
			if (currentBlobIdent.isTree())
				parentPath = currentBlobIdent.path;
			else if (currentBlobIdent.path.contains("/"))
				parentPath = StringUtils.substringBeforeLast(currentBlobIdent.path, "/");
			else
				parentPath = null;
			
			while (parentPath != null) {
				pickerState.add(new BlobIdent(commitId.name(), parentPath, FileMode.TYPE_TREE));
				if (parentPath.contains("/"))
					parentPath = StringUtils.substringBeforeLast(parentPath, "/");
				else
					parentPath = null;
			}
		}
		return pickerState;
	}
	
	private Component newPickExistingPanel() {
		Fragment fragment;
		BlobRenderContext context = markdownEditor.getBlobRenderContext();
		if (context != null) {
			fragment = new Fragment(CONTENT_ID, "pickBlobFrag", this);
			BlobIdentFilter blobIdentFilter = new BlobIdentFilter() {
				@Override
				public boolean filter(BlobIdent blobIdent) {
					if (isImage) {
						if (blobIdent.isTree()) {
							return true;
						} else {
							String mimetype = MIME_TYPES.getContentType(new File(blobIdent.path));
							return mimetype.split("/")[0].equals("image");
						}
					} else {
						return true;
					}
				}
			};
			
			ObjectId commitId = resolveCommitId(context);
			
			Set<BlobIdent> filePickerState = getPickerState(commitId, context.getBlobIdent(), 
					WebSession.get().getMetaData(FILE_PICKER_STATE));
			
			IModel<Project> projectModel = new AbstractReadOnlyModel<Project>() {

				@Override
				public Project getObject() {
					return markdownEditor.getBlobRenderContext().getProject();
				}
				
			};
			fragment.add(new BlobSelector("files", projectModel, commitId) {

				@Override
				protected void onSelect(AjaxRequestTarget target, String blobPath) {
					BlobIdent blobIdent = new BlobIdent(context.getBlobIdent().revision, blobPath, 
							FileMode.REGULAR_FILE.getBits());
					String baseUrl = context.getDirectoryUrl();
					String referenceUrl = urlFor(ProjectBlobPage.class, 
							ProjectBlobPage.paramsOf(context.getProject(), blobIdent)).toString();
					String relativized = PathUtils.relativize(baseUrl, referenceUrl);	
					markdownEditor.insertUrl(target, isImage, relativized, linkText!=null?linkText:blobIdent.getName(), null);
					onClose(target);
				}

				@Override
				protected BlobIdentFilter getBlobIdentFilter() {
					return blobIdentFilter;
				}

				@Override
				protected void onStateChange() {
					HashSet<String> expandedPaths = new HashSet<>();
					for (BlobIdent blobIdent: filePickerState)
						expandedPaths.add(blobIdent.path);
					WebSession.get().setMetaData(FILE_PICKER_STATE, expandedPaths);
				}

				@Override
				protected Set<BlobIdent> getState() {
					return filePickerState;
				}
				
			});
		} else {
			AttachmentSupport attachmentSupport = Preconditions.checkNotNull(markdownEditor.getAttachmentSupport());
			IModel<List<String>> attachmentsModel;
			if (isImage) {
				fragment = new Fragment(CONTENT_ID, "pickAttachedImageFrag", this);
				attachmentsModel = new LoadableDetachableModel<>() {

					@Override
					protected List<String> load() {
						List<String> attachments = new ArrayList<>();
						for (String attachment : attachmentSupport.getAttachments()) {
							if (markdownEditor.isWebSafeImage(attachment))
								attachments.add(attachment);
						}
						return attachments;
					}

				};
			} else {
				fragment = new Fragment(CONTENT_ID, "pickAttachedFileFrag", this);
				attachmentsModel = new LoadableDetachableModel<>() {

					@Override
					protected List<String> load() {
						return attachmentSupport.getAttachments();
					}

				};
			}
			fragment.add(new ListView<>("attachments", attachmentsModel) {

				@Override
				protected void populateItem(final ListItem<String> item) {
					String attachmentName = item.getModelObject();
					String attachmentUrl = attachmentSupport.getAttachmentUrlPath(attachmentName);

					AjaxLink<Void> selectLink = new AjaxLink<Void>("select") {

						@Override
						public void onClick(AjaxRequestTarget target) {
							markdownEditor.insertUrl(target, isImage, attachmentUrl,
									linkText != null ? linkText : attachmentName, null);
							onClose(target);
						}

					};
					
					if (isImage)
						selectLink.add(new AttributeAppender("data-tippy-content", _T("Insert this image")));
					else
						selectLink.add(new AttributeAppender("data-tippy-content", _T("Insert link to this file")));

					if (isImage)
						selectLink.add(new ExternalImage("image", escapeHtml5(attachmentUrl)));
					else
						selectLink.add(new Label("file", escapeHtml5(attachmentName)));
					
					item.add(selectLink);

					var deleteLink = new AjaxLink<Void>("delete") {

						@Override
						protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
							super.updateAjaxAttributes(attributes);
							attributes.getAjaxCallListeners().add(new ConfirmClickListener(MessageFormat.format(_T("Do you really want to delete \"{0}\"?"), attachmentName)));
						}

						@Override
						protected void onConfigure() {
							super.onConfigure();
							setVisible(attachmentSupport.canDeleteAttachment());
						}

						@Override
						public void onClick(AjaxRequestTarget target) {
							attachmentSupport.deleteAttachemnt(attachmentName);
							target.add(fragment);
						}

					};
					if (isImage)
						deleteLink.add(new AttributeAppender("data-tippy-content", _T("Remove this image")));
					else
						deleteLink.add(new AttributeAppender("data-tippy-content", _T("Remove this file")));
					item.add(deleteLink);
				}

				@Override
				protected void onConfigure() {
					super.onConfigure();
					setVisible(!attachmentsModel.getObject().isEmpty());
				}
				
			});
			fragment.add(new WebMarkupContainer("noAttachments") {
				@Override
				protected void onConfigure() {
					super.onConfigure();
					setVisible(attachmentsModel.getObject().isEmpty());
				}
			});
		}
		fragment.setOutputMarkupId(true);
		return fragment;
	}
	
	private Component newUploadPanel() {
		Fragment fragment;

		IModel<String> model = new PropertyModel<String>(this, "uploadId");
		String acceptedFiles;
		if (isImage)
			acceptedFiles = "image/*";
		else
			acceptedFiles = null;
		
		AttachmentSupport attachmentSupport = markdownEditor.getAttachmentSupport();
		if (attachmentSupport != null) {
			fragment = new Fragment(CONTENT_ID, "uploadAttachmentFrag", this);
			
			Form<?> form = new Form<Void>("form") {

				@Override
				protected void onSubmit() {
					super.onSubmit();
					
					AjaxRequestTarget target = RequestCycle.get().find(AjaxRequestTarget.class);
					String attachmentName;
					var upload = getUploadManager().getUpload(uploadId);
					try {
						for (var item : upload.getItems()) {
							try (InputStream is = item.getInputStream()) {
								attachmentName = attachmentSupport.saveAttachment(
										FilenameUtils.sanitizeFileName(FileUpload.getFileName(item)), is);
								markdownEditor.insertUrl(target, isImage,
										attachmentSupport.getAttachmentUrlPath(attachmentName),
										linkText!=null?linkText:attachmentName, null);
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
					} finally {
						upload.clear();
					}
					onClose(target);
				}

				@Override
				protected void onFileUploadException(FileUploadException e, Map<String, Object> model) {
					throw new RuntimeException(e);
				}
				
			};
			form.setMaxSize(Bytes.bytes(attachmentSupport.getAttachmentMaxSize()));
			form.setMultiPart(true);
			form.add(new FencedFeedbackPanel("feedback", form));
			
			int maxFilesize = (int) (attachmentSupport.getAttachmentMaxSize()/1024/1024);
			if (maxFilesize <= 0)
				maxFilesize = 1;
			form.add(new DropzoneField("file", model, acceptedFiles, 1, maxFilesize)
					.setRequired(true).setLabel(Model.of(_T("Attachment"))));
			
			form.add(new AjaxButton("insert"){});
			form.add(new AjaxLink<Void>("cancel") {
				@Override
				public void onClick(AjaxRequestTarget target) {
					onClose(target);
				}
			});
			fragment.add(form);
		} else {
			int maxUploadFileSize = OneDev.getInstance(SettingManager.class).getPerformanceSetting().getMaxUploadFileSize();
			fragment = new Fragment(CONTENT_ID, "uploadBlobFrag", this);
			Form<?> form = new Form<Void>("form");
			form.setMultiPart(true);
			form.setFileMaxSize(Bytes.megabytes(maxUploadFileSize));
			add(form);
			
			FencedFeedbackPanel feedback = new FencedFeedbackPanel("feedback", form);
			feedback.setOutputMarkupPlaceholderTag(true);
			form.add(feedback);
			
			form.add(new DropzoneField("file", model, acceptedFiles, 1, maxUploadFileSize)
					.setRequired(true).setLabel(Model.of(_T("Attachment"))));

			form.add(new TextField<String>("directory", new IModel<String>() {

				@Override
				public void detach() {
				}

				@Override
				public String getObject() {
					return WebSession.get().getMetaData(UPLOAD_DIRECTORY);
				}

				@Override
				public void setObject(String object) {
					WebSession.get().setMetaData(UPLOAD_DIRECTORY, object);
				}
				
			})); 

			BlobRenderContext context = Preconditions.checkNotNull(markdownEditor.getBlobRenderContext());
			ObjectId commitId = resolveCommitId(context);
			Set<BlobIdent> folderPickerState = getPickerState(commitId, context.getBlobIdent(), 
					WebSession.get().getMetaData(FOLDER_PICKER_STATE));
			
			form.add(new DropdownLink("select") {

				@Override
				protected Component newContent(String id, FloatingPanel dropdown) {
					return new BlobFolderSelector(id, commitId) {

						@Override
						protected void onSelect(AjaxRequestTarget target, BlobIdent blobIdent) {
							dropdown.close();
							
							String relativePath = PathUtils.relativize(context.getDirectory(), blobIdent.path);
							String script = String.format("$('form.upload-blob .directory input').val('%s');", 
									JavaScriptEscape.escapeJavaScript(relativePath));
							target.appendJavaScript(script);
						}

						@Override
						protected Project getProject() {
							return markdownEditor.getBlobRenderContext().getProject();
						}

						@Override
						protected void onStateChange() {
							HashSet<String> expandedPaths = new HashSet<>();
							for (BlobIdent blobIdent: folderPickerState)
								expandedPaths.add(blobIdent.path);
							WebSession.get().setMetaData(FOLDER_PICKER_STATE, expandedPaths);
						}

						@Override
						protected Set<BlobIdent> getState() {
							return folderPickerState;
						}
						
					};
				}
				
			});

			form.add(new AjaxLink<Void>("cancel") {
				@Override
				public void onClick(AjaxRequestTarget target) {
					onClose(target);
				}
			});
			
			ReferenceInputBehavior behavior = new ReferenceInputBehavior() {
				
				@Override
				protected Project getProject() {
					return markdownEditor.getBlobRenderContext().getProject();
				}
				
			};
			form.add(new TextArea<String>("commitMessage", new PropertyModel<String>(this, "commitMessage")).add(behavior));
			
			form.add(new AjaxButton("insert") {

				@Override
				protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
					super.onSubmit(target, form);

					BlobRenderContext context = Preconditions.checkNotNull(markdownEditor.getBlobRenderContext());
					String commitMessage = InsertUrlPanel.this.commitMessage;
					if (StringUtils.isBlank(commitMessage))
						commitMessage = _T("Add files via upload");

					var upload = getUploadManager().getUpload(uploadId);
					try {
						String directory = WebSession.get().getMetaData(UPLOAD_DIRECTORY);
						context.onCommitted(null, context.uploadFiles(upload, directory, commitMessage));
						String fileName = FileUpload.getFileName(upload.getItems().iterator().next());
						String url;
						if (directory != null) 
							url = StringUtils.stripEnd(directory, "/") + "/" + UrlUtils.encodePath(fileName);
						else 
							url = UrlUtils.encodePath(fileName);
						markdownEditor.insertUrl(target, isImage, url, linkText!=null?linkText:fileName, null);
						upload.clear();
						onClose(target);
					} catch (ExplicitException e) {
						form.error(e.getMessage());
						target.add(feedback);
					} catch (Exception e) {
						upload.clear();
						throw ExceptionUtils.unchecked(e);
					}
				}

				@Override
				protected void onError(AjaxRequestTarget target, Form<?> form) {
					super.onError(target, form);
					target.add(feedback);
				}
				
			});
			
			fragment.add(form);
		}
		
		fragment.setOutputMarkupId(true);
		return fragment;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new Label("title", isImage? _T("Insert Image") : _T("Insert Link")));
		
		if (markdownEditor.getBlobRenderContext() == null && markdownEditor.getAttachmentSupport() == null) {
			add(newInputUrlPanel());
		} else {
			String tabInputUrl = _T("Input URL");
			String tabPickExisting = _T("Pick Existing");
			String tabUpload = _T("Upload");

			Fragment fragment = new Fragment(CONTENT_ID, "tabbedFrag", this);
			List<Tab> tabs = new ArrayList<>();
			AjaxActionTab inputUrlTab = new AjaxActionTab(Model.of(tabInputUrl)) {

				@Override
				protected void onSelect(AjaxRequestTarget target, Component tabLink) {
					Component content = newInputUrlPanel();
					target.add(content);
					fragment.replace(content);
					WebSession.get().setMetaData(ACTIVE_TAB, tabInputUrl);
				}
				
			};
			tabs.add(inputUrlTab);

			AjaxActionTab pickExistingTab = new AjaxActionTab(Model.of(tabPickExisting)) {

				@Override
				protected void onSelect(AjaxRequestTarget target, Component tabLink) {
					Component content = newPickExistingPanel();
					target.add(content);
					fragment.replace(content);
					WebSession.get().setMetaData(ACTIVE_TAB, tabPickExisting);
				}
				
			};
			tabs.add(pickExistingTab);
			
			AjaxActionTab uploadTab = new AjaxActionTab(Model.of(tabUpload)) {

				@Override
				protected void onSelect(AjaxRequestTarget target, Component tabLink) {
					Component content = newUploadPanel();
					target.add(content);
					fragment.replace(content);
					WebSession.get().setMetaData(ACTIVE_TAB, tabUpload);
				}
				
			};
			tabs.add(uploadTab);
			
			fragment.add(new Tabbable("tabs", tabs));
			
			inputUrlTab.setSelected(false);
			String activeTab = WebSession.get().getMetaData(ACTIVE_TAB);
			if (tabPickExisting.equals(activeTab)) {
				pickExistingTab.setSelected(true);
				fragment.add(newPickExistingPanel());
			} else if (tabUpload.equals(activeTab)) {
				uploadTab.setSelected(true);
				fragment.add(newUploadPanel());
			} else {
				inputUrlTab.setSelected(true);
				fragment.add(newInputUrlPanel());
			}
			add(fragment);
		}
		
		add(new AjaxLink<Void>("close") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				onClose(target);
			}
			
		});
		
	}
	
	private UploadManager getUploadManager() {
		return OneDev.getInstance(UploadManager.class);
	}
	
	protected abstract void onClose(AjaxRequestTarget target);
}
