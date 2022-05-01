/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.generator;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Predicate;

import javax.lang.model.SourceVersion;

import org.springframework.aot.generator.CodeContribution;
import org.springframework.aot.generator.ProtectedAccess;
import org.springframework.aot.generator.ResolvableTypeGenerator;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.BeanInfoFactory;
import org.springframework.beans.ExtendedBeanInfoFactory;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.generator.config.BeanDefinitionRegistrar;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.ResolvableType;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.CodeBlock.Builder;
import org.springframework.javapoet.support.MultiStatement;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * A {@link BeanFactoryContribution} that registers a bean with the bean
 * factory.
 *
 * @author Stephane Nicoll
 * @since 6.0
 */
public class BeanRegistrationBeanFactoryContribution implements BeanFactoryContribution {

	private static final BeanInfoFactory beanInfoFactory = new ExtendedBeanInfoFactory();

	private static final ResolvableTypeGenerator typeGenerator = new ResolvableTypeGenerator();

	private final String beanName;

	private final RootBeanDefinition beanDefinition;

	private final BeanInstantiationGenerator beanInstantiationGenerator;

	@Nullable
	private final DefaultBeanRegistrationContributionProvider innerBeanRegistrationContributionProvider;

	private int nesting = 0;

	BeanRegistrationBeanFactoryContribution(String beanName, RootBeanDefinition beanDefinition,
			BeanInstantiationGenerator beanInstantiationGenerator,
			@Nullable DefaultBeanRegistrationContributionProvider innerBeanRegistrationContributionProvider) {
		this.beanName = beanName;
		this.beanDefinition = beanDefinition;
		this.beanInstantiationGenerator = beanInstantiationGenerator;
		this.innerBeanRegistrationContributionProvider = innerBeanRegistrationContributionProvider;
	}

	public BeanRegistrationBeanFactoryContribution(String beanName, RootBeanDefinition beanDefinition,
			BeanInstantiationGenerator beanInstantiationGenerator) {
		this(beanName, beanDefinition, beanInstantiationGenerator, null);
	}

	String getBeanName() {
		return this.beanName;
	}

	RootBeanDefinition getBeanDefinition() {
		return this.beanDefinition;
	}

	@Override
	public void applyTo(BeanFactoryInitialization initialization) {
		RuntimeHints runtimeHints = initialization.generatedTypeContext().runtimeHints();
		registerRuntimeHints(runtimeHints);
		CodeContribution beanInstanceContribution = generateBeanInstance(runtimeHints);
		// Write everything in one place
		ProtectedAccess protectedAccess = beanInstanceContribution.protectedAccess();
		protectedAccess.analyze(this.beanDefinition.getResolvableType());
		initialization.contribute(protectedAccess, this::registerBeanMethodName, code ->
				code.add(generateBeanRegistration(runtimeHints, beanInstanceContribution.statements())));
	}

	/**
	 * Register the necessary hints that are required to process the bean
	 * registration generated by this instance.
	 * @param runtimeHints the runtime hints to use
	 */
	void registerRuntimeHints(RuntimeHints runtimeHints) {
		String[] initMethodNames = this.beanDefinition.getInitMethodNames();
		if (!ObjectUtils.isEmpty(initMethodNames)) {
			registerInitDestroyMethodsRuntimeHints(initMethodNames, runtimeHints);
		}
		String[] destroyMethodNames = this.beanDefinition.getDestroyMethodNames();
		if (!ObjectUtils.isEmpty(destroyMethodNames)) {
			registerInitDestroyMethodsRuntimeHints(destroyMethodNames, runtimeHints);
		}
		registerPropertyValuesRuntimeHints(runtimeHints);
	}

	/**
	 * Generate the necessary code to register a {@link BeanDefinition} in the
	 * bean registry.
	 * @param runtimeHints the hints to use
	 * @param beanInstanceStatements the {@linkplain MultiStatement statements}
	 * to create and initialize the bean instance
	 * @return bean registration code
	 */
	CodeBlock generateBeanRegistration(RuntimeHints runtimeHints, MultiStatement beanInstanceStatements) {
		BeanParameterGenerator parameterGenerator = createBeanParameterGenerator(runtimeHints);
		Generator generator = new Generator(parameterGenerator);
		return generator.generateBeanRegistration(beanInstanceStatements);
	}

	/**
	 * Generate the necessary code to create a {@link BeanDefinition}.
	 * @param runtimeHints the hints to use
	 * @return bean definition code
	 */
	CodeBlock generateBeanDefinition(RuntimeHints runtimeHints) {
		CodeContribution beanInstanceContribution = generateBeanInstance(runtimeHints);
		BeanParameterGenerator parameterGenerator = createBeanParameterGenerator(runtimeHints);
		Generator generator = new Generator(parameterGenerator);
		return generator.generateBeanDefinition(beanInstanceContribution.statements());
	}

	private BeanParameterGenerator createBeanParameterGenerator(RuntimeHints runtimeHints) {
		return new BeanParameterGenerator(beanDefinition ->
				generateInnerBeanDefinition(beanDefinition, runtimeHints));
	}

	/**
	 * Return the predicate to use to include Bean Definition
	 * {@link AttributeAccessor attributes}.
	 * @return the bean definition's attributes include filter
	 */
	protected Predicate<String> getAttributeFilter() {
		return candidate -> false;
	}

	/**
	 * Specify if the creator {@link Executable} should be defined. By default,
	 * a creator is specified if the {@code instanceSupplier} callback is used
	 * with an {@code instanceContext} callback.
	 * @param instanceCreator the executable to use to instantiate the bean
	 * @return {@code true} to declare the creator
	 */
	protected boolean shouldDeclareCreator(Executable instanceCreator) {
		if (instanceCreator instanceof Method) {
			return true;
		}
		if (instanceCreator instanceof Constructor<?> constructor) {
			int minArgs = isInnerClass(constructor.getDeclaringClass()) ? 2 : 1;
			return instanceCreator.getParameterCount() >= minArgs;
		}
		return false;
	}

	/**
	 * Return the necessary code to instantiate and post-process a bean.
	 * @param runtimeHints the {@link RuntimeHints} to use
	 * @return a code contribution that provides an initialized bean instance
	 */
	protected CodeContribution generateBeanInstance(RuntimeHints runtimeHints) {
		return this.beanInstantiationGenerator.generateBeanInstantiation(runtimeHints);
	}

	private void registerInitDestroyMethodsRuntimeHints(String[] methodNames, RuntimeHints runtimeHints) {
		for (String methodName : methodNames) {
			Method method = ReflectionUtils.findMethod(getUserBeanClass(), methodName);
			if (method != null) {
				runtimeHints.reflection().registerMethod(method, hint -> hint.withMode(ExecutableMode.INVOKE));
			}
		}
	}

	private void registerPropertyValuesRuntimeHints(RuntimeHints runtimeHints) {
		if (!this.beanDefinition.hasPropertyValues()) {
			return;
		}
		BeanInfo beanInfo = getBeanInfo(this.beanDefinition.getResolvableType().toClass());
		if (beanInfo != null) {
			ReflectionHints reflectionHints = runtimeHints.reflection();
			this.beanDefinition.getPropertyValues().getPropertyValueList().forEach(propertyValue -> {
				Method writeMethod = findWriteMethod(beanInfo, propertyValue.getName());
				if (writeMethod != null) {
					reflectionHints.registerMethod(writeMethod, hint -> hint.withMode(ExecutableMode.INVOKE));
				}
			});
		}
	}

	@Nullable
	private BeanInfo getBeanInfo(Class<?> beanType) {
		try {
			BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanType);
			if (beanInfo != null) {
				return beanInfo;
			}
			return Introspector.getBeanInfo(beanType, Introspector.IGNORE_ALL_BEANINFO);
		}
		catch (IntrospectionException ex) {
			return null;
		}
	}

	@Nullable
	private Method findWriteMethod(BeanInfo beanInfo, String propertyName) {
		return Arrays.stream(beanInfo.getPropertyDescriptors())
				.filter(pd -> propertyName.equals(pd.getName()))
				.map(java.beans.PropertyDescriptor::getWriteMethod)
				.filter(Objects::nonNull).findFirst().orElse(null);
	}

	protected CodeBlock initializeBeanDefinitionRegistrar() {
		return CodeBlock.of("$T.of($S, ", BeanDefinitionRegistrar.class, this.beanName);
	}

	private Class<?> getUserBeanClass() {
		return ClassUtils.getUserClass(this.beanDefinition.getResolvableType().toClass());
	}

	private void handleCreatorReference(Builder code, Executable creator) {
		if (creator instanceof Method) {
			code.add(".withFactoryMethod($T.class, $S", creator.getDeclaringClass(), creator.getName());
			if (creator.getParameterCount() > 0) {
				code.add(", ");
			}
		}
		else {
			code.add(".withConstructor(");
		}
		code.add(BeanParameterGenerator.INSTANCE.generateExecutableParameterTypes(creator));
		code.add(")");
	}

	private CodeBlock generateInnerBeanDefinition(BeanDefinition beanDefinition, RuntimeHints runtimeHints) {
		if (this.innerBeanRegistrationContributionProvider == null) {
			throw new IllegalStateException("This generator does not handle inner bean definition " + beanDefinition);
		}
		BeanRegistrationBeanFactoryContribution innerBeanRegistrationContribution = this.innerBeanRegistrationContributionProvider
				.getInnerBeanRegistrationContribution(this, beanDefinition);
		innerBeanRegistrationContribution.nesting = this.nesting + 1;
		innerBeanRegistrationContribution.registerRuntimeHints(runtimeHints);
		return innerBeanRegistrationContribution.generateBeanDefinition(runtimeHints);
	}

	private String registerBeanMethodName() {
		Executable instanceCreator = this.beanInstantiationGenerator.getInstanceCreator();
		if (instanceCreator instanceof Method method) {
			String target = (isValidName(this.beanName)) ? this.beanName : method.getName();
			return String.format("register%s_%s", method.getDeclaringClass().getSimpleName(), target);
		}
		else if (instanceCreator.getDeclaringClass().getEnclosingClass() != null) {
			String target = (isValidName(this.beanName)) ? this.beanName : getUserBeanClass().getSimpleName();
			Class<?> enclosingClass = instanceCreator.getDeclaringClass().getEnclosingClass();
			return String.format("register%s_%s", enclosingClass.getSimpleName(), target);
		}
		else {
			String target = (isValidName(this.beanName)) ? this.beanName : getUserBeanClass().getSimpleName();
			return "register" + StringUtils.capitalize(target);
		}
	}

	private boolean isValidName(@Nullable String name) {
		return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
	}

	private String determineVariableName(String name) {
		return name + "_".repeat(this.nesting);
	}

	private static boolean isInnerClass(Class<?> type) {
		return type.isMemberClass() && !java.lang.reflect.Modifier.isStatic(type.getModifiers());
	}

	class Generator {

		private final BeanParameterGenerator parameterGenerator;

		private final RootBeanDefinition beanDefinition;

		Generator(BeanParameterGenerator parameterGenerator) {
			this.parameterGenerator = parameterGenerator;
			this.beanDefinition = BeanRegistrationBeanFactoryContribution.this.beanDefinition;
		}

		CodeBlock generateBeanRegistration(MultiStatement instanceStatements) {
			CodeBlock.Builder code = CodeBlock.builder();
			initializeBeanDefinitionRegistrar(instanceStatements, code);
			code.addStatement(".register(beanFactory)");
			return code.build();
		}

		CodeBlock generateBeanDefinition(MultiStatement instanceStatements) {
			CodeBlock.Builder code = CodeBlock.builder();
			initializeBeanDefinitionRegistrar(instanceStatements, code);
			code.add(".toBeanDefinition()");
			return code.build();
		}

		private void initializeBeanDefinitionRegistrar(MultiStatement instanceStatements, Builder code) {
			Executable instanceCreator = BeanRegistrationBeanFactoryContribution.this.beanInstantiationGenerator.getInstanceCreator();
			code.add(BeanRegistrationBeanFactoryContribution.this.initializeBeanDefinitionRegistrar());
			generateBeanType(code);
			code.add(")");
			boolean shouldDeclareCreator = shouldDeclareCreator(instanceCreator);
			if (shouldDeclareCreator) {
				handleCreatorReference(code, instanceCreator);
			}
			code.add("\n").indent().indent();
			code.add(".instanceSupplier(");
			code.add(instanceStatements.toLambdaBody());
			code.add(")").unindent().unindent();
			handleBeanDefinitionMetadata(code);
		}

		private void generateBeanType(Builder code) {
			ResolvableType resolvableType = this.beanDefinition.getResolvableType();
			if (resolvableType.hasGenerics() && !hasUnresolvedGenerics(resolvableType)) {
				code.add(typeGenerator.generateTypeFor(resolvableType));
			}
			else {
				code.add("$T.class", getUserBeanClass());
			}
		}

		private boolean hasUnresolvedGenerics(ResolvableType resolvableType) {
			if (resolvableType.hasUnresolvableGenerics()) {
				return true;
			}
			for (ResolvableType generic : resolvableType.getGenerics()) {
				if (hasUnresolvedGenerics(generic)) {
					return true;
				}
			}
			return false;
		}

		private void handleBeanDefinitionMetadata(Builder code) {
			String bdVariable = determineVariableName("bd");
			MultiStatement statements = new MultiStatement();
			String[] initMethodNames = this.beanDefinition.getInitMethodNames();
			if (!ObjectUtils.isEmpty(initMethodNames)) {
				handleInitMethodNames(statements, bdVariable, initMethodNames);
			}
			String[] destroyMethodNames = this.beanDefinition.getDestroyMethodNames();
			if (!ObjectUtils.isEmpty(destroyMethodNames)) {
				handleDestroyMethodNames(statements, bdVariable, destroyMethodNames);
			}
			if (this.beanDefinition.isPrimary()) {
				statements.addStatement("$L.setPrimary(true)", bdVariable);
			}
			String scope = this.beanDefinition.getScope();
			if (StringUtils.hasText(scope) && !ConfigurableBeanFactory.SCOPE_SINGLETON.equals(scope)) {
				statements.addStatement("$L.setScope($S)", bdVariable, scope);
			}
			String[] dependsOn = this.beanDefinition.getDependsOn();
			if (!ObjectUtils.isEmpty(dependsOn)) {
				statements.addStatement("$L.setDependsOn($L)", bdVariable,
						this.parameterGenerator.generateParameterValue(dependsOn));
			}
			if (this.beanDefinition.isLazyInit()) {
				statements.addStatement("$L.setLazyInit(true)", bdVariable);
			}
			if (!this.beanDefinition.isAutowireCandidate()) {
				statements.addStatement("$L.setAutowireCandidate(false)", bdVariable);
			}
			if (this.beanDefinition.isSynthetic()) {
				statements.addStatement("$L.setSynthetic(true)", bdVariable);
			}
			if (this.beanDefinition.getRole() != BeanDefinition.ROLE_APPLICATION) {
				statements.addStatement("$L.setRole($L)", bdVariable, this.beanDefinition.getRole());
			}
			Map<Integer, ValueHolder> indexedArgumentValues = this.beanDefinition.getConstructorArgumentValues()
					.getIndexedArgumentValues();
			if (!indexedArgumentValues.isEmpty()) {
				handleArgumentValues(statements, bdVariable, indexedArgumentValues);
			}
			if (this.beanDefinition.hasPropertyValues()) {
				handlePropertyValues(statements, bdVariable, this.beanDefinition.getPropertyValues());
			}
			if (this.beanDefinition.attributeNames().length > 0) {
				handleAttributes(statements, bdVariable);
			}
			if (statements.isEmpty()) {
				return;
			}
			code.add(statements.toLambda(".customize((" + bdVariable + ") ->"));
			code.add(")");
		}

		private void handleInitMethodNames(MultiStatement statements, String bdVariable, String[] initMethodNames) {
			if (initMethodNames.length == 1) {
				statements.addStatement("$L.setInitMethodName($S)", bdVariable, initMethodNames[0]);
			}
			else {
				statements.addStatement("$L.setInitMethodNames($L)", bdVariable,
						this.parameterGenerator.generateParameterValue(initMethodNames));
			}
		}

		private void handleDestroyMethodNames(MultiStatement statements, String bdVariable, String[] destroyMethodNames) {
			if (destroyMethodNames.length == 1) {
				statements.addStatement("$L.setDestroyMethodName($S)", bdVariable, destroyMethodNames[0]);
			}
			else {
				statements.addStatement("$L.setDestroyMethodNames($L)", bdVariable,
						this.parameterGenerator.generateParameterValue(destroyMethodNames));
			}
		}

		private void handleArgumentValues(MultiStatement statements, String bdVariable,
				Map<Integer, ValueHolder> indexedArgumentValues) {
			if (indexedArgumentValues.size() == 1) {
				Entry<Integer, ValueHolder> entry = indexedArgumentValues.entrySet().iterator().next();
				statements.addStatement(generateArgumentValue(bdVariable + ".getConstructorArgumentValues().",
						entry.getKey(), entry.getValue()));
			}
			else {
				String avVariable = determineVariableName("argumentValues");
				statements.addStatement("$T $L = $L.getConstructorArgumentValues()", ConstructorArgumentValues.class, avVariable, bdVariable);
				statements.addAll(indexedArgumentValues.entrySet(), entry -> generateArgumentValue(avVariable + ".",
						entry.getKey(), entry.getValue()));
			}
		}

		private CodeBlock generateArgumentValue(String prefix, Integer index, ValueHolder valueHolder) {
			Builder code = CodeBlock.builder();
			code.add(prefix);
			code.add("addIndexedArgumentValue($L, ", index);
			Object value = valueHolder.getValue();
			code.add(this.parameterGenerator.generateParameterValue(value));
			code.add(")");
			return code.build();
		}

		private void handlePropertyValues(MultiStatement statements, String bdVariable,
				PropertyValues propertyValues) {
			PropertyValue[] properties = propertyValues.getPropertyValues();
			if (properties.length == 1) {
				statements.addStatement(generatePropertyValue(bdVariable + ".getPropertyValues().", properties[0]));
			}
			else {
				String pvVariable = determineVariableName("propertyValues");
				statements.addStatement("$T $L = $L.getPropertyValues()", MutablePropertyValues.class, pvVariable, bdVariable);
				for (PropertyValue property : properties) {
					statements.addStatement(generatePropertyValue(pvVariable + ".", property));
				}
			}
		}

		private CodeBlock generatePropertyValue(String prefix, PropertyValue property) {
			Builder code = CodeBlock.builder();
			code.add(prefix);
			code.add("addPropertyValue($S, ", property.getName());
			Object value = property.getValue();
			code.add(this.parameterGenerator.generateParameterValue(value));
			code.add(")");
			return code.build();
		}

		private void handleAttributes(MultiStatement statements, String bdVariable) {
			String[] attributeNames = this.beanDefinition.attributeNames();
			Predicate<String> filter = getAttributeFilter();
			for (String attributeName : attributeNames) {
				if (filter.test(attributeName)) {
					Object value = this.beanDefinition.getAttribute(attributeName);
					Builder code = CodeBlock.builder();
					code.add("$L.setAttribute($S, ", bdVariable, attributeName);
					code.add((this.parameterGenerator.generateParameterValue(value)));
					code.add(")");
					statements.addStatement(code.build());
				}
			}
		}
	}

}
