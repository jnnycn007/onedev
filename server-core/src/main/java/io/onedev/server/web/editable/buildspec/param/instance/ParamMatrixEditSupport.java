package io.onedev.server.web.editable.buildspec.param.instance;

import java.io.Serializable;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.model.IModel;

import io.onedev.server.buildspec.param.instance.ParamInstances;
import io.onedev.server.util.ReflectionUtils;
import io.onedev.server.web.editable.EditSupport;
import io.onedev.server.web.editable.PropertyContext;
import io.onedev.server.web.editable.PropertyDescriptor;
import io.onedev.server.web.editable.PropertyEditor;
import io.onedev.server.web.editable.PropertyViewer;
import io.onedev.server.annotation.ParamSpecProvider;

public class ParamMatrixEditSupport implements EditSupport {

	@Override
	public PropertyContext<?> getEditContext(PropertyDescriptor descriptor) {
		if (List.class.isAssignableFrom(descriptor.getPropertyClass())) {
			Class<?> elementClass = ReflectionUtils.getCollectionElementClass(descriptor.getPropertyGetter().getGenericReturnType());
			if (elementClass == ParamInstances.class && descriptor.getPropertyGetter().getAnnotation(ParamSpecProvider.class) != null) {
				return new PropertyContext<List<Serializable>>(descriptor) {

					@Override
					public PropertyViewer renderForView(String componentId, IModel<List<Serializable>> model) {
						return new PropertyViewer(componentId, descriptor) {

							@Override
							protected Component newContent(String id, PropertyDescriptor propertyDescriptor) {
								if (model.getObject() != null && !model.getObject().isEmpty()) 
									return new ParamMatrixViewPanel(id, model.getObject());
								else 
									return new WebMarkupContainer(id);
							}
							
						};
					}

					@Override
					public PropertyEditor<List<Serializable>> renderForEdit(String componentId, IModel<List<Serializable>> model) {
						return new ParamMatrixEditPanel(componentId, descriptor, model);
					}
					
				};
			}
		}
		return null;
	}

	@Override
	public int getPriority() {
		return 0;
	}
	
}
