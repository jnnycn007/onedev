package io.onedev.server.web.editable;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.google.common.collect.Sets;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.annotation.DependsOn;
import io.onedev.server.annotation.ShowCondition;
import io.onedev.server.annotation.SubscriptionRequired;
import io.onedev.server.util.BeanUtils;
import io.onedev.server.util.ComponentContext;
import io.onedev.server.util.EditContext;
import io.onedev.server.util.ReflectionUtils;

public class PropertyDescriptor implements Serializable {

	private static final long serialVersionUID = 1L;

	private final Class<?> beanClass;
	
	private final String propertyName;
	
	private boolean propertyExcluded;
	
	private boolean propertyHidden;
	
	private Set<String> dependencyPropertyNames = new HashSet<>();
	
	private transient Method propertyGetter;
	
	private transient Method propertySetter;
	
	public PropertyDescriptor(Class<?> beanClass, String propertyName) {
		this.beanClass = beanClass;
		this.propertyName = propertyName;
	}
	
	public PropertyDescriptor(Method propertyGetter) {
		this.beanClass = propertyGetter.getDeclaringClass();
		this.propertyName = BeanUtils.getPropertyName(propertyGetter);
	}
	
	public PropertyDescriptor(PropertyDescriptor propertyDescriptor) {
		this.beanClass = propertyDescriptor.getBeanClass();
		this.propertyName = propertyDescriptor.getPropertyName();
		this.propertyExcluded = propertyDescriptor.isPropertyExcluded();
	}
	
	public Class<?> getBeanClass() {
		return beanClass;
	}

	public String getPropertyName() {
		return propertyName;
	}

	public Method getPropertyGetter() {
		if (propertyGetter == null)
			propertyGetter = BeanUtils.getGetter(beanClass, propertyName);
		return propertyGetter;
	}
	
	public Method getPropertySetter() {
		if (propertySetter == null) 
			propertySetter = BeanUtils.getSetter(getPropertyGetter());
		return propertySetter;
	}

	public boolean isPropertyExcluded() {
		return propertyExcluded;
	}

	public void setPropertyExcluded(boolean propertyExcluded) {
		this.propertyExcluded = propertyExcluded;
	}

	public boolean isPropertyHidden() {
		return propertyHidden;
	}

	public void setPropertyHidden(boolean propertyHidden) {
		this.propertyHidden = propertyHidden;
	}

	public void copyProperty(Object fromBean, Object toBean) {
		setPropertyValue(toBean, getPropertyValue(fromBean));
	}

	public Object getPropertyValue(Object bean) {
		try {
			return getPropertyGetter().invoke(bean);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	public void setPropertyValue(Object bean, Object propertyValue) {
		try {
			getPropertySetter().invoke(bean, propertyValue);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	public Class<?> getPropertyClass() {
		return getPropertyGetter().getReturnType();
	}

	public boolean isPropertyRequired() {
		Size size;
		return getPropertyGetter().getReturnType().isPrimitive()
				|| findAnnotation(NotNull.class) != null 
				|| findAnnotation(NotEmpty.class) != null
				|| (size = findAnnotation(Size.class)) != null && size.min()>=1;
	}

	@Nullable
	private <T extends Annotation> T findAnnotation(Class<T> annotationClass) {
		Class<?> current = beanClass;
		while (current != null) {
			Method method;
			try {
				method = current.getMethod(getPropertyGetter().getName());
				T annotation = method.getAnnotation(annotationClass);
				if (annotation != null)
					return annotation;
			} catch (NoSuchMethodException | SecurityException e) {
			}
			current = current.getSuperclass();
		}
		return null;
	}
	
	public boolean isPropertyVisible(Map<String, ComponentContext> componentContexts, BeanDescriptor beanDescriptor) {
		return isPropertyVisible(componentContexts, beanDescriptor, Sets.newHashSet());
	}
	
	public boolean isSubscriptionRequired() {
		return getPropertyGetter().getAnnotation(SubscriptionRequired.class) != null;
	}
	
	private boolean isPropertyVisible(Map<String, ComponentContext> componentContexts, BeanDescriptor beanDescriptor, Set<String> checkedPropertyNames) {
		if (!checkedPropertyNames.add(getPropertyName()))
			return false;
		
		Set<String> prevDependencyPropertyNames = new HashSet<>(getDependencyPropertyNames());
		var componentContext = componentContexts.get(getPropertyName());
		if (componentContext != null)
			ComponentContext.push(componentContext);
		try {
			/* 
			 * Sometimes, the dependency may include properties introduced while evaluating available choices 
			 * of a choice input. We clear it temporarily here in order to make visibility of the property 
			 * consistent of BeanViewer, Issue.isFieldVisible, or Build.isParamVisible 
			 */
			getDependencyPropertyNames().clear();
			ShowCondition showCondition = getPropertyGetter().getAnnotation(ShowCondition.class);
			if (showCondition != null && !(boolean)ReflectionUtils.invokeStaticMethod(getBeanClass(), showCondition.value()))
				return false;
			DependsOn dependsOn = getPropertyGetter().getAnnotation(DependsOn.class);
			if (dependsOn != null) {
				var dependencyProperty = beanDescriptor.getProperty(dependsOn.property());
				if (dependencyProperty == null) {
					throw new ExplicitException("Dependency property not found: " + dependsOn.property());
				}
				var dependencyPropertyValue = EditContext.get().getInputValue(dependsOn.property());				
				if (dependsOn.value().length() != 0) {
					if (dependencyPropertyValue != null && dependencyPropertyValue.toString().equals(dependsOn.value())) {
						if (dependsOn.inverse())
							return false;
					} else if (!dependsOn.inverse()) {
						return false;
					}
				} else {
					if (dependencyProperty.getPropertyClass() == boolean.class) {
						boolean requiredPropertyValue = !dependsOn.inverse();
						if (requiredPropertyValue != (boolean)dependencyPropertyValue)
							return false;
					} else if (dependencyProperty.getPropertyClass() == int.class || dependencyProperty.getPropertyClass() == long.class || dependencyProperty.getPropertyClass() == double.class || dependencyProperty.getPropertyClass() == float.class) {
						int dependencyPropertyIntValue = (int) dependencyPropertyValue;
						if (dependsOn.inverse() && dependencyPropertyIntValue != 0 || !dependsOn.inverse() && dependencyPropertyIntValue == 0)
							return false;
					} else {
						if (dependsOn.inverse() && dependencyPropertyValue != null || !dependsOn.inverse() && dependencyPropertyValue == null)
							return false;
					}
				}
			}
			getDependencyPropertyNames().remove(getPropertyName());
			for (String dependencyPropertyName: getDependencyPropertyNames()) {
				Set<String> copyOfCheckedPropertyNames = new HashSet<>(checkedPropertyNames);
				if (!beanDescriptor.getProperty(dependencyPropertyName).isPropertyVisible(componentContexts, beanDescriptor, copyOfCheckedPropertyNames))
					return false;
			}
			return true;
		} finally {
			getDependencyPropertyNames().addAll(prevDependencyPropertyNames);
			if (componentContext != null)
				ComponentContext.pop();
		}
	}
	
	public Set<String> getDependencyPropertyNames() {
		return dependencyPropertyNames;
	}
	
	public void setDependencyPropertyNames(Set<String> dependencyPropertyNames) {
		this.dependencyPropertyNames = dependencyPropertyNames;
	}
	
	public String getDisplayName() {
		return EditableUtils.getDisplayName(getPropertyGetter());
	}
	
	@Nullable
	public String getDescription() {
		return EditableUtils.getDescription(getPropertyGetter());
	}

}
