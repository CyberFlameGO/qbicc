package org.qbicc.plugin.patcher;

import org.qbicc.context.ClassContext;
import org.qbicc.plugin.core.ConditionEvaluation;
import org.qbicc.type.definition.ConstructorResolver;
import org.qbicc.type.definition.DefinedTypeDefinition;
import org.qbicc.type.definition.FieldResolver;
import org.qbicc.type.definition.InitializerResolver;
import org.qbicc.type.definition.MethodResolver;
import org.qbicc.type.definition.element.ConstructorElement;
import org.qbicc.type.definition.element.FieldElement;
import org.qbicc.type.definition.element.MethodElement;
import org.qbicc.type.descriptor.MethodDescriptor;
import org.qbicc.type.descriptor.TypeDescriptor;

/**
 *
 */
final class PatchedTypeBuilder implements DefinedTypeDefinition.Builder.Delegating {
    private final ClassContext classContext;
    private final ClassContextPatchInfo contextInfo;
    private final DefinedTypeDefinition.Builder delegate;
    private ClassPatchInfo classPatchInfo;

    PatchedTypeBuilder(ClassContext classContext, ClassContextPatchInfo contextInfo, DefinedTypeDefinition.Builder delegate) {
        this.classContext = classContext;
        this.contextInfo = contextInfo;
        this.delegate = delegate;
    }

    @Override
    public DefinedTypeDefinition.Builder getDelegate() {
        return delegate;
    }

    @Override
    public void setName(String internalName) {
        if (contextInfo.isPatchClass(internalName)) {
            throw new IllegalStateException("A patch class was found for loading: " + internalName);
        }
        classPatchInfo = contextInfo.get(internalName);
        if (classPatchInfo != null) {
            // no further changes may be registered
            synchronized (classPatchInfo) {
                classPatchInfo.commit();
            }
        }
        getDelegate().setName(internalName);
    }

    @Override
    public void setInitializer(InitializerResolver resolver, int index) {
        ClassPatchInfo classPatchInfo = this.classPatchInfo;
        if (classPatchInfo != null) {
            InitializerPatchInfo patchInfo;
            synchronized (classPatchInfo) {
                if (classPatchInfo.isDeletedInitializer()) {
                    getDelegate().setInitializer(resolver, -1);
                    return;
                }
                patchInfo = classPatchInfo.getReplacementInitializerInfo();
            }
            ConditionEvaluation ce = ConditionEvaluation.get(classContext.getCompilationContext());
            if (patchInfo == null || !ce.evaluateConditions(classContext, patchInfo, patchInfo.getAnnotation())) {
                getDelegate().setInitializer(resolver, index);
            } else {
                getDelegate().setInitializer(patchInfo.getInitializerResolver(), patchInfo.getIndex());
            }
        } else {
            getDelegate().setInitializer(resolver, index);
        }
    }

    @Override
    public void addField(FieldResolver resolver, int index, String name, TypeDescriptor descriptor) {
        ClassPatchInfo classPatchInfo = this.classPatchInfo;
        if (classPatchInfo != null) {
            ConditionEvaluation ce = ConditionEvaluation.get(classContext.getCompilationContext());
            FieldPatchInfo patchInfo;
            synchronized (classPatchInfo) {
                FieldDeleteInfo delInfo = classPatchInfo.getDeletedFieldInfo(name, descriptor);
                if (delInfo != null && ce.evaluateConditions(classContext, delInfo, delInfo.getAnnotation())) {
                    // skip completely
                    return;
                }
                patchInfo = classPatchInfo.getReplacementFieldInfo(name, descriptor);
            }
            if (patchInfo == null || !ce.evaluateConditions(classContext, patchInfo, patchInfo.getAnnotation())) {
                getDelegate().addField(resolver, index, name, descriptor);
            } else if (patchInfo.getAdditionalModifiers() == 0) {
                getDelegate().addField(patchInfo.getFieldResolver(), patchInfo.getIndex(), name, descriptor);
            } else {
                getDelegate().addField(new PatcherFieldResolver(patchInfo), patchInfo.getIndex(), name, descriptor);
            }
        } else {
            getDelegate().addField(resolver, index, name, descriptor);
        }
    }

    @Override
    public void addConstructor(ConstructorResolver resolver, int index, MethodDescriptor descriptor) {
        ClassPatchInfo classPatchInfo = this.classPatchInfo;
        if (classPatchInfo != null) {
            ConditionEvaluation ce = ConditionEvaluation.get(classContext.getCompilationContext());
            ConstructorPatchInfo constructorInfo;
            synchronized (classPatchInfo) {
                ConstructorDeleteInfo delInfo = classPatchInfo.getDeletedConstructorInfo(descriptor);
                if (delInfo != null && ce.evaluateConditions(classContext, delInfo, delInfo.getAnnotation())) {
                    // skip completely
                    return;
                }
                constructorInfo = classPatchInfo.getReplacementConstructorInfo(descriptor);
            }
            if (constructorInfo == null || !ce.evaluateConditions(classContext, constructorInfo, constructorInfo.getAnnotation())) {
                getDelegate().addConstructor(resolver, index, descriptor);
            } else if (constructorInfo.getAdditionalModifiers() == 0) {
                getDelegate().addConstructor(constructorInfo.getConstructorResolver(), constructorInfo.getIndex(), descriptor);
            } else {
                getDelegate().addConstructor(new PatcherConstructorResolver(constructorInfo), constructorInfo.getIndex(), descriptor);
            }
        } else {
            getDelegate().addConstructor(resolver, index, descriptor);
        }
    }

    @Override
    public void addMethod(MethodResolver resolver, int index, String name, MethodDescriptor descriptor) {
        ClassPatchInfo classPatchInfo = this.classPatchInfo;
        if (classPatchInfo != null) {
            ConditionEvaluation ce = ConditionEvaluation.get(classContext.getCompilationContext());
            MethodPatchInfo methodInfo;
            synchronized (classPatchInfo) {
                MethodDeleteInfo delInfo = classPatchInfo.getDeletedMethodInfo(name, descriptor);
                if (delInfo != null && ce.evaluateConditions(classContext, delInfo, delInfo.getAnnotation())) {
                    // skip completely
                    return;
                }
                methodInfo = classPatchInfo.getReplacementMethodInfo(name, descriptor);
            }
            if (methodInfo == null || !ce.evaluateConditions(classContext, methodInfo, methodInfo.getAnnotation())) {
                getDelegate().addMethod(resolver, index, name, descriptor);
            } else if (methodInfo.getAdditionalModifiers() == 0) {
                getDelegate().addMethod(methodInfo.getMethodResolver(), methodInfo.getIndex(), name, descriptor);
            } else {
                getDelegate().addMethod(new PatcherMethodResolver(methodInfo), methodInfo.getIndex(), name, descriptor);
            }
        } else {
            getDelegate().addMethod(resolver, index, name, descriptor);
        }
    }

    @Override
    public DefinedTypeDefinition build() {
        // add injected members
        ClassPatchInfo classPatchInfo = this.classPatchInfo;
        if (classPatchInfo != null) {
            synchronized (classPatchInfo) {
                for (FieldPatchInfo fieldInfo : classPatchInfo.getInjectedFields()) {
                    // inject
                    getDelegate().addField(new PatcherFieldResolver(fieldInfo), fieldInfo.getIndex(), fieldInfo.getName(), fieldInfo.getDescriptor());
                }
                for (ConstructorPatchInfo ctorInfo : classPatchInfo.getInjectedConstructors()) {
                    // inject
                    getDelegate().addConstructor(new PatcherConstructorResolver(ctorInfo), ctorInfo.getIndex(), ctorInfo.getDescriptor());
                }
                for (MethodPatchInfo methodInfo : classPatchInfo.getInjectedMethods()) {
                    // inject
                    getDelegate().addMethod(new PatcherMethodResolver(methodInfo), methodInfo.getIndex(), methodInfo.getName(), methodInfo.getDescriptor());
                }
            }
        }
        return getDelegate().build();
    }

    static class PatcherMethodResolver implements MethodResolver {
        private final MethodPatchInfo methodInfo;

        PatcherMethodResolver(final MethodPatchInfo methodInfo) {
            this.methodInfo = methodInfo;
        }

        @Override
        public MethodElement resolveMethod(int index, DefinedTypeDefinition enclosing, MethodElement.Builder builder) {
            MethodElement methodElement = methodInfo.getMethodResolver().resolveMethod(index, enclosing, builder);
            methodElement.setModifierFlags(methodInfo.getAdditionalModifiers());
            return methodElement;
        }
    }

    static class PatcherConstructorResolver implements ConstructorResolver {
        private final ConstructorPatchInfo constructorInfo;

        PatcherConstructorResolver(final ConstructorPatchInfo constructorInfo) {
            this.constructorInfo = constructorInfo;
        }

        @Override
        public ConstructorElement resolveConstructor(int index, DefinedTypeDefinition enclosing, ConstructorElement.Builder builder) {
            ConstructorElement constructorElement = constructorInfo.getConstructorResolver().resolveConstructor(index, enclosing, builder);
            constructorElement.setModifierFlags(constructorInfo.getAdditionalModifiers());
            return constructorElement;
        }
    }

    static class PatcherFieldResolver implements FieldResolver {
        private final FieldPatchInfo fieldInfo;

        PatcherFieldResolver(final FieldPatchInfo fieldInfo) {
            this.fieldInfo = fieldInfo;
        }

        @Override
        public FieldElement resolveField(int index, DefinedTypeDefinition enclosing, FieldElement.Builder builder) {
            FieldElement fieldElement = fieldInfo.getFieldResolver().resolveField(index, enclosing, builder);
            fieldElement.setModifierFlags(fieldInfo.getAdditionalModifiers());
            return fieldElement;
        }
    }
}
