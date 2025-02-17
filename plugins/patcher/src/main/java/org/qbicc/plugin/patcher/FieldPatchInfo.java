package org.qbicc.plugin.patcher;

import org.qbicc.context.Locatable;
import org.qbicc.context.Location;
import org.qbicc.type.annotation.Annotation;
import org.qbicc.type.definition.FieldResolver;
import org.qbicc.type.definition.InitializerResolver;
import org.qbicc.type.descriptor.TypeDescriptor;

/**
 *
 */
final class FieldPatchInfo extends MemberPatchInfo implements Locatable {
    private final InitializerResolver initializerResolver;
    private final int initializerResolverIndex;
    private final FieldResolver fieldResolver;
    private final TypeDescriptor descriptor;
    private final String name;

    FieldPatchInfo(String internalName, int index, int modifiers, InitializerResolver initializerResolver, int initializerResolverIndex, FieldResolver fieldResolver, TypeDescriptor descriptor, String name, Annotation annotation) {
        super(index, modifiers, internalName, annotation);
        this.initializerResolver = initializerResolver;
        this.initializerResolverIndex = initializerResolverIndex;
        this.fieldResolver = fieldResolver;
        this.descriptor = descriptor;
        this.name = name;
    }

    InitializerResolver getInitializerResolver() {
        return initializerResolver;
    }

    int getInitializerResolverIndex() {
        return initializerResolverIndex;
    }

    FieldResolver getFieldResolver() {
        return fieldResolver;
    }

    TypeDescriptor getDescriptor() {
        return descriptor;
    }

    public String getName() {
        return name;
    }

    @Override
    public Location getLocation() {
        return ClassContextPatchInfo.getFieldLocation(getInternalName(), name);
    }
}
