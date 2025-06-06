package io.onedev.server.web.page.project.builds.detail.artifacts;

import static io.onedev.server.util.IOUtils.BUFFER_SIZE;
import static io.onedev.server.web.translation.Translation._T;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.fileupload.FileItem;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.feedback.FencedFeedbackPanel;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.util.lang.Bytes;
import org.glassfish.jersey.client.ClientProperties;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.LockUtils;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.OneDev;
import io.onedev.server.StorageManager;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.Build;
import io.onedev.server.util.FilenameUtils;
import io.onedev.server.util.IOUtils;
import io.onedev.server.web.component.dropzonefield.DropzoneField;
import io.onedev.server.web.upload.FileUpload;
import io.onedev.server.web.upload.UploadManager;

public abstract class ArtifactUploadPanel extends Panel {

	private String directory;
	
	private String uploadId;
	
	public ArtifactUploadPanel(String id) {
		super(id);
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		SettingManager settingManager = OneDev.getInstance(SettingManager.class);
		int maxUploadFileSize = settingManager.getPerformanceSetting().getMaxUploadFileSize();
		
		Form<?> form = new Form<Void>("form");
		form.setMultiPart(true);
		form.setFileMaxSize(Bytes.megabytes(maxUploadFileSize));
		add(form);
		
		form.add(new AjaxLink<Void>("close") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				onCancel(target);
			}
			
		});
		
		FencedFeedbackPanel feedback = new FencedFeedbackPanel("feedback", form);
		feedback.setOutputMarkupPlaceholderTag(true);
		form.add(feedback);
		
		DropzoneField dropzone = new DropzoneField(
				"files", 
				new PropertyModel<String>(this, "uploadId"), 
				null, 0, maxUploadFileSize);
		dropzone.setRequired(true).setLabel(Model.of(_T("File")));
		form.add(dropzone);
		
		form.add(new AjaxButton("upload") {

			private String getArtifactPath(FileItem file) {
				String artifactPath = FilenameUtils.sanitizeFileName(FileUpload.getFileName(file));
				if (directory != null)
					artifactPath = directory + "/" + artifactPath;
				return artifactPath;
			}
			
			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				super.onSubmit(target, form);
				
				if (directory != null && directory.contains("..")) {
					error(_T("'..' is not allowed in the directory"));
					target.add(feedback);
				} else {
					ProjectManager projectManager = OneDev.getInstance(ProjectManager.class);
					ClusterManager clusterManager = OneDev.getInstance(ClusterManager.class);
					
					Long projectId = getBuild().getProject().getId();
					String activeServer = projectManager.getActiveServer(projectId, true);
					var upload = getUploadManager().getUpload(uploadId);
					try {
						if (activeServer.equals(clusterManager.getLocalServerAddress())) {
							LockUtils.write(getBuild().getArtifactsLockName(), () -> {
								StorageManager storageManager = OneDev.getInstance(StorageManager.class);
								var artifactsDir = storageManager.initArtifactsDir(getBuild().getProject().getId(), getBuild().getNumber());
								for (var item : upload.getItems()) {
									String filePath = getArtifactPath(item);
									File file = new File(artifactsDir, filePath);
									FileUtils.createDir(file.getParentFile());
									try (var is = item.getInputStream();
										 var os = new BufferedOutputStream(new FileOutputStream(file), BUFFER_SIZE)) {
										IOUtils.copy(is, os, BUFFER_SIZE);
									}
								}
								projectManager.directoryModified(projectId, artifactsDir);
								return null;
							});
						} else {
							Client client = ClientBuilder.newClient();
							client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
							try {
								String serverUrl = clusterManager.getServerUrl(activeServer);
								for (var item : upload.getItems()) {
									String filePath = getArtifactPath(item);
									WebTarget jerseyTarget = client.target(serverUrl)
											.path("~api/cluster/artifact")
											.queryParam("projectId", projectId)
											.queryParam("buildNumber", getBuild().getNumber())
											.queryParam("artifactPath", filePath);
									Invocation.Builder builder = jerseyTarget.request();
									builder.header(HttpHeaders.AUTHORIZATION,
											KubernetesHelper.BEARER + " " + clusterManager.getCredential());

									StreamingOutput os = output -> {
										try (InputStream is = item.getInputStream()) {
											IOUtils.copy(is, output, BUFFER_SIZE);
										} finally {
											output.close();
										}
									};

									try (Response response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
										KubernetesHelper.checkStatus(response);
									}
								}
							} finally {
								client.close();
							}
						}
					} finally {
						upload.clear();
					}
					
					onUploaded(target);
				}
			}

			@Override
			protected void onError(AjaxRequestTarget target, Form<?> form) {
				super.onError(target, form);
				target.add(feedback);
			}
			
		});
		
		form.add(new TextField<String>("directory", new PropertyModel<String>(this, "directory")));
		
		form.add(new AjaxLink<Void>("cancel") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				onCancel(target);
			}
			
		});
	}
	
	private UploadManager getUploadManager() {
		return OneDev.getInstance(UploadManager.class);
	}

	public abstract void onUploaded(AjaxRequestTarget target);
	
	public abstract void onCancel(AjaxRequestTarget target);
	
	protected abstract Build getBuild();
	
}
