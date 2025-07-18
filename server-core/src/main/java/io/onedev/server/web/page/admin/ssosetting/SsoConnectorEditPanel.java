package io.onedev.server.web.page.admin.ssosetting;

import static io.onedev.server.web.translation.Translation._T;

import java.util.List;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.request.cycle.RequestCycle;

import io.onedev.server.OneDev;
import io.onedev.server.data.migration.VersionedXmlDoc;
import io.onedev.server.entitymanager.AuditManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.sso.SsoConnector;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.util.Path;
import io.onedev.server.util.PathNode;
import io.onedev.server.web.ajaxlistener.ConfirmLeaveListener;
import io.onedev.server.web.editable.BeanContext;
import io.onedev.server.web.editable.BeanEditor;

abstract class SsoConnectorEditPanel extends Panel {

	private final int connectorIndex;
	
	public SsoConnectorEditPanel(String id, int connectorIndex) {
		super(id);
	
		this.connectorIndex = connectorIndex;
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		SsoConnectorBean bean = new SsoConnectorBean();
		if (connectorIndex != -1)
			bean.setConnector(SerializationUtils.clone(getConnectors().get(connectorIndex)));

		Form<?> form = new Form<Void>("form") {

			@Override
			protected void onError() {
				super.onError();
				RequestCycle.get().find(AjaxRequestTarget.class).add(this);
			}
			
		};
		
		form.add(new AjaxLink<Void>("close") {

			@Override
			protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
				super.updateAjaxAttributes(attributes);
				attributes.getAjaxCallListeners().add(new ConfirmLeaveListener(SsoConnectorEditPanel.this));
			}

			@Override
			public void onClick(AjaxRequestTarget target) {
				onCancel(target);
			}
			
		});
		
		BeanEditor editor = BeanContext.edit("editor", bean);
		form.add(editor);
		form.add(new AjaxButton("save") {

			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				super.onSubmit(target, form);

				if (connectorIndex != -1) { 
					SsoConnector oldConnector = getConnectors().get(connectorIndex);
					if (!bean.getConnector().getName().equals(oldConnector.getName()) 
							&& getConnector(bean.getConnector().getName()) != null) {
						editor.error(new Path(new PathNode.Named("connector"), new PathNode.Named("name")),
								_T("This name has already been used by another provider"));
					}
				} else if (getConnector(bean.getConnector().getName()) != null) {
					editor.error(new Path(new PathNode.Named("connector"), new PathNode.Named("name")),
							_T("This name has already been used by another provider"));
				}

				if (editor.isValid()) {
					OneDev.getInstance(TransactionManager.class).run(new Runnable() {

						@Override
						public void run() {
							var auditManager = OneDev.getInstance(AuditManager.class);
							if (connectorIndex != -1) {
								var oldConnector = getConnectors().set(connectorIndex, bean.getConnector());
								var oldAuditContent = VersionedXmlDoc.fromBean(oldConnector).toXML();
								var newAuditContent = VersionedXmlDoc.fromBean(bean.getConnector()).toXML();
								auditManager.audit(null, "changed sso connector \"" + bean.getConnector().getName() + "\"", oldAuditContent, newAuditContent);
							} else {
								getConnectors().add(bean.getConnector());
								var newAuditContent = VersionedXmlDoc.fromBean(bean.getConnector()).toXML();
								auditManager.audit(null, "created sso connector \"" + bean.getConnector().getName() + "\"", null, newAuditContent);
							}
							OneDev.getInstance(SettingManager.class).saveSsoConnectors(getConnectors());
						}
						
					});
					onSave(target);
				} else {
					target.add(form);
				}
			}
			
		});
		
		form.add(new AjaxLink<Void>("cancel") {

			@Override
			protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
				super.updateAjaxAttributes(attributes);
				attributes.getAjaxCallListeners().add(new ConfirmLeaveListener(SsoConnectorEditPanel.this));
			}

			@Override
			public void onClick(AjaxRequestTarget target) {
				onCancel(target);
			}
			
		});
		form.setOutputMarkupId(true);
		
		add(form);
	}
	
	@Nullable
	private SsoConnector getConnector(String name) {
		for (SsoConnector connector: getConnectors()) {
			if (connector.getName().equals(name))
				return connector;
		}
		return null;
	}

	protected abstract List<SsoConnector> getConnectors();
	
	protected abstract void onSave(AjaxRequestTarget target);
	
	protected abstract void onCancel(AjaxRequestTarget target);

}
