package org.qbicc.interpreter.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.smallrye.common.constraint.Assert;
import org.qbicc.context.ClassContext;
import org.qbicc.context.CompilationContext;
import org.qbicc.interpreter.Memory;
import org.qbicc.interpreter.Signal;
import org.qbicc.interpreter.Thrown;
import org.qbicc.interpreter.Vm;
import org.qbicc.interpreter.VmClass;
import org.qbicc.interpreter.VmClassLoader;
import org.qbicc.interpreter.VmInvokable;
import org.qbicc.interpreter.VmObject;
import org.qbicc.interpreter.VmThread;
import org.qbicc.machine.arch.Platform;
import org.qbicc.plugin.coreclasses.CoreClasses;
import org.qbicc.plugin.layout.Layout;
import org.qbicc.type.ClassObjectType;
import org.qbicc.type.definition.DefinedTypeDefinition;
import org.qbicc.type.definition.LoadedTypeDefinition;
import org.qbicc.type.definition.element.ConstructorElement;
import org.qbicc.type.definition.element.ExecutableElement;
import org.qbicc.type.definition.element.FieldElement;
import org.qbicc.type.definition.element.GlobalVariableElement;
import org.qbicc.type.definition.element.MethodElement;

public final class VmImpl implements Vm {
    private final CompilationContext ctxt;
    private final Map<GlobalVariableElement, Memory64Impl> globals = new ConcurrentHashMap<>();
    private final Map<String, VmStringImpl> interned = new ConcurrentHashMap<>();
    private final VmClassLoaderImpl bootstrapClassLoader;
    private final AtomicBoolean initialized = new AtomicBoolean();

    final boolean wideRefs;
    final MemoryImpl emptyMemory;

    boolean bootstrapComplete;

    // core classes
    final VmClassImpl objectClass;
    final VmClassClassImpl classClass;
    final VmStringClassImpl stringClass;
    final int stringCoderOffset;
    final int stringValueOffset;
    final VmThreadClassImpl threadClass;
    final VmThrowableClassImpl throwableClass;
    final VmClassLoaderClassImpl classLoaderClass;

    // primitive classes
    final VmPrimitiveClassImpl byteClass;
    final VmPrimitiveClassImpl shortClass;
    final VmPrimitiveClassImpl intClass;
    final VmPrimitiveClassImpl longClass;
    final VmPrimitiveClassImpl floatClass;
    final VmPrimitiveClassImpl doubleClass;
    final VmPrimitiveClassImpl charClass;
    final VmPrimitiveClassImpl booleanClass;
    final VmPrimitiveClassImpl voidClass;

    // array classes
    final int arrayLengthOffset;

    final int byteArrayContentOffset;
    final VmByteArrayClassImpl byteArrayClass;
    final int shortArrayContentOffset;
    final VmShortArrayClassImpl shortArrayClass;
    final int intArrayContentOffset;
    final VmIntArrayClassImpl intArrayClass;
    final int longArrayContentOffset;
    final VmLongArrayClassImpl longArrayClass;
    final int floatArrayContentOffset;
    final VmFloatArrayClassImpl floatArrayClass;
    final int doubleArrayContentOffset;
    final VmDoubleArrayClassImpl doubleArrayClass;
    final int charArrayContentOffset;
    final VmCharArrayClassImpl charArrayClass;
    final int booleanArrayContentOffset;
    final VmBooleanArrayClassImpl booleanArrayClass;

    final int refArrayContentOffset; // special

    // error classes
    final VmThrowableClassImpl errorClass;

    final VmThrowableClassImpl linkageErrorClass;

    final VmThrowableClassImpl incompatibleClassChangeErrorClass;
    final VmThrowableClassImpl noClassDefFoundErrorClass;

    final VmThrowableClassImpl noSuchMethodErrorClass;

    final VmClassImpl stackTraceElementClass;

    // regular classes
    volatile VmClassImpl propertiesClass;

    volatile MethodElement setPropertyMethod;

    VmImpl(final CompilationContext ctxt) {
        this.ctxt = ctxt;
        bootstrapComplete = false;
        // force all fields to be populated so the injections are visible to us
        CoreClasses coreClasses = CoreClasses.get(ctxt);
        ctxt.getExceptionField();
        wideRefs = ctxt.getTypeSystem().getReferenceSize() == 8;
        emptyMemory = wideRefs ? Memory64Impl.EMPTY : Memory32Impl.EMPTY;
        ClassContext bcc = ctxt.getBootstrapClassContext();
        classClass = new VmClassClassImpl(this);
        objectClass = classClass.getSuperClass();
        classLoaderClass = new VmClassLoaderClassImpl(this, bcc.findDefinedType("java/lang/ClassLoader").load(), null);
        LoadedTypeDefinition stringDef = bcc.findDefinedType("java/lang/String").load();
        stringClass = new VmStringClassImpl(this, stringDef);
        FieldElement coderField = stringDef.findField("coder");
        FieldElement valueField = stringDef.findField("value");
        Layout layout = Layout.getForInterpreter(ctxt);
        Layout.LayoutInfo stringLayout = layout.getInstanceLayoutInfo(stringDef);
        stringCoderOffset = stringLayout.getMember(coderField).getOffset();
        stringValueOffset = stringLayout.getMember(valueField).getOffset();
        threadClass = new VmThreadClassImpl(this, bcc.findDefinedType("java/lang/Thread").load(), null);
        throwableClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/Throwable").load(), null);

        byteClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getByteArrayTypeDefinition(), "byte");
        shortClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getShortArrayTypeDefinition(), "short");
        intClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getIntArrayTypeDefinition(), "int");
        longClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getLongArrayTypeDefinition(), "long");
        floatClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getFloatArrayTypeDefinition(), "float");
        doubleClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getDoubleArrayTypeDefinition(), "double");
        charClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getCharArrayTypeDefinition(), "char");
        booleanClass = new VmPrimitiveClassImpl(this, classClass, coreClasses.getBooleanArrayTypeDefinition(), "boolean");
        voidClass = new VmPrimitiveClassImpl(this, classClass, null, "void");

        FieldElement arrayLengthField = coreClasses.getArrayLengthField();
        LoadedTypeDefinition arrayBaseClassDef = arrayLengthField.getEnclosingType().load();
        arrayLengthOffset = layout.getInstanceLayoutInfo(arrayBaseClassDef).getMember(arrayLengthField).getOffset();

        FieldElement byteArrayContentField = coreClasses.getByteArrayContentField();
        LoadedTypeDefinition byteArrayClassDef = byteArrayContentField.getEnclosingType().load();
        byteArrayClass = new VmByteArrayClassImpl(this, classClass, byteArrayClassDef, byteClass);
        byteArrayContentOffset = layout.getInstanceLayoutInfo(byteArrayClassDef).getMember(byteArrayContentField).getOffset();

        FieldElement shortArrayContentField = coreClasses.getShortArrayContentField();
        LoadedTypeDefinition shortArrayClassDef = shortArrayContentField.getEnclosingType().load();
        shortArrayClass = new VmShortArrayClassImpl(this, classClass, shortArrayClassDef, shortClass);
        shortArrayContentOffset = layout.getInstanceLayoutInfo(shortArrayClassDef).getMember(shortArrayContentField).getOffset();

        FieldElement intArrayContentField = coreClasses.getIntArrayContentField();
        LoadedTypeDefinition intArrayClassDef = intArrayContentField.getEnclosingType().load();
        intArrayClass = new VmIntArrayClassImpl(this, classClass, intArrayClassDef, intClass);
        intArrayContentOffset = layout.getInstanceLayoutInfo(intArrayClassDef).getMember(intArrayContentField).getOffset();

        FieldElement longArrayContentField = coreClasses.getLongArrayContentField();
        LoadedTypeDefinition longArrayClassDef = longArrayContentField.getEnclosingType().load();
        longArrayClass = new VmLongArrayClassImpl(this, classClass, longArrayClassDef, longClass);
        longArrayContentOffset = layout.getInstanceLayoutInfo(longArrayClassDef).getMember(longArrayContentField).getOffset();

        FieldElement floatArrayContentField = coreClasses.getFloatArrayContentField();
        LoadedTypeDefinition floatArrayClassDef = floatArrayContentField.getEnclosingType().load();
        floatArrayClass = new VmFloatArrayClassImpl(this, classClass, floatArrayClassDef, floatClass);
        floatArrayContentOffset = layout.getInstanceLayoutInfo(floatArrayClassDef).getMember(floatArrayContentField).getOffset();

        FieldElement doubleArrayContentField = coreClasses.getDoubleArrayContentField();
        LoadedTypeDefinition doubleArrayClassDef = doubleArrayContentField.getEnclosingType().load();
        doubleArrayClass = new VmDoubleArrayClassImpl(this, classClass, doubleArrayClassDef, doubleClass);
        doubleArrayContentOffset = layout.getInstanceLayoutInfo(doubleArrayClassDef).getMember(doubleArrayContentField).getOffset();

        FieldElement charArrayContentField = coreClasses.getCharArrayContentField();
        LoadedTypeDefinition charArrayClassDef = charArrayContentField.getEnclosingType().load();
        charArrayClass = new VmCharArrayClassImpl(this, classClass, charArrayClassDef, charClass);
        charArrayContentOffset = layout.getInstanceLayoutInfo(charArrayClassDef).getMember(charArrayContentField).getOffset();

        FieldElement booleanArrayContentField = coreClasses.getBooleanArrayContentField();
        LoadedTypeDefinition booleanArrayClassDef = booleanArrayContentField.getEnclosingType().load();
        booleanArrayClass = new VmBooleanArrayClassImpl(this, classClass, booleanArrayClassDef, booleanClass);
        booleanArrayContentOffset = layout.getInstanceLayoutInfo(booleanArrayClassDef).getMember(booleanArrayContentField).getOffset();

        refArrayContentOffset = layout.getInstanceLayoutInfo(coreClasses.getReferenceArrayTypeDefinition()).getMember(coreClasses.getRefArrayContentField()).getOffset();

        classClass.setName(this);
        objectClass.setName(this);
        classLoaderClass.setName(this);
        stringClass.setName(this);
        threadClass.setName(this);
        throwableClass.setName(this);
        byteClass.setName(this);
        shortClass.setName(this);
        intClass.setName(this);
        longClass.setName(this);
        floatClass.setName(this);
        doubleClass.setName(this);
        charClass.setName(this);
        booleanClass.setName(this);
        voidClass.setName(this);

        byteArrayClass.setName(this);
        shortArrayClass.setName(this);
        intArrayClass.setName(this);
        longArrayClass.setName(this);
        floatArrayClass.setName(this);
        doubleArrayClass.setName(this);
        charArrayClass.setName(this);
        booleanArrayClass.setName(this);

        // throwables
        errorClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/Error").load(), null);
        errorClass.setName(this);

        // errors
        linkageErrorClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/LinkageError").load(), null);
        linkageErrorClass.setName(this);

        // linkage errors
        incompatibleClassChangeErrorClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/IncompatibleClassChangeError").load(), null);
        incompatibleClassChangeErrorClass.setName(this);
        noClassDefFoundErrorClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/NoClassDefFoundError").load(), null);
        noClassDefFoundErrorClass.setName(this);

        // incompatible class change errors
        noSuchMethodErrorClass = new VmThrowableClassImpl(this, bcc.findDefinedType("java/lang/NoSuchMethodError").load(), null);
        noSuchMethodErrorClass.setName(this);

        stackTraceElementClass = new VmClassImpl(this, bcc.findDefinedType("java/lang/StackTraceElement").load(), null);
        stackTraceElementClass.setName(this);

        // set up the bootstrap class loader *last*
        bootstrapClassLoader = new VmClassLoaderImpl(classLoaderClass, this);

        // now register the classes
        bootstrapClassLoader.registerClass("java/lang/Object", objectClass);
        bootstrapClassLoader.registerClass("java/lang/Class", classClass);
        bootstrapClassLoader.registerClass("java/lang/String", stringClass);
        bootstrapClassLoader.registerClass("java/lang/Thread", threadClass);
        bootstrapClassLoader.registerClass("java/lang/Throwable", throwableClass);
        bootstrapClassLoader.registerClass("java/lang/ClassLoader", classLoaderClass);

        bootstrapClassLoader.registerClass("java/lang/Error", errorClass);
        bootstrapClassLoader.registerClass("java/lang/LinkageError", linkageErrorClass);
        bootstrapClassLoader.registerClass("java/lang/IncompatibleClassChangeError", incompatibleClassChangeErrorClass);
        bootstrapClassLoader.registerClass("java/lang/NoClassDefFoundError", noClassDefFoundErrorClass);
        bootstrapClassLoader.registerClass("java/lang/NoSuchMethodError", noSuchMethodErrorClass);

        bootstrapClassLoader.registerClass("java/lang/StackTraceElement", stackTraceElementClass);

        throwableClass.initializeConstantStaticFields(); // Has constant String fields that can't be initialized when we first process the class
    }

    VmClassLoaderImpl getBootstrapClassLoader() {
        return bootstrapClassLoader;
    }

    public CompilationContext getCompilationContext() {
        return ctxt;
    }

    @Override
    public void initialize() {
        VmThreadImpl vmThread = (VmThreadImpl) Vm.requireCurrentThread();
        if (initialized.compareAndSet(false, true)) {

            propertiesClass = bootstrapClassLoader.loadClass("java/util/Properties");

            LoadedTypeDefinition propertiesTypeDef = propertiesClass.getTypeDefinition();
            int idx = propertiesTypeDef.findSingleMethodIndex(me -> me.nameEquals("setProperty"));
            if (idx == -1) {
                throw new IllegalStateException("Missing required method in VM");
            }
            setPropertyMethod = propertiesTypeDef.getMethod(idx);


            // Register all hooks
            VmClassLoaderImpl bootstrapClassLoader = this.bootstrapClassLoader;

            // VMHelpers
            VmClassImpl vmHelpersClass = bootstrapClassLoader.loadClass("org/qbicc/runtime/main/VMHelpers");

            vmHelpersClass.registerInvokable("get_class", (thread, target, args) -> ((VmObjectImpl) args.get(0)).getVmClass());
            vmHelpersClass.registerInvokable("classForName", (thread, target, args) -> {
                VmClassLoaderImpl classLoader = (VmClassLoaderImpl) args.get(2);
                if (classLoader == null) {
                    classLoader = bootstrapClassLoader;
                }
                VmClassImpl clazz = classLoader.loadClass(((VmStringImpl) args.get(0)).getContent().replace('.', '/'));
                if (((Boolean) args.get(1)).booleanValue()) {
                    clazz.initialize((VmThreadImpl) thread);
                }
                return clazz;
            });

            // ObjectModel
            VmClassImpl objectModelClass = bootstrapClassLoader.loadClass("org/qbicc/runtime/main/ObjectModel");

            objectModelClass.registerInvokable("type_id_of", (thread, target, args) -> ((VmObjectImpl) args.get(0)).getObjectTypeId());

            // Unsafe
            VmClassImpl unsafeClass = bootstrapClassLoader.loadClass("jdk/internal/misc/Unsafe");

            unsafeClass.registerInvokable("ensureClassInitialized", (thread, target, args) -> {
                ((VmClassImpl) args.get(0)).initialize((VmThreadImpl) thread);
                return null;
            });

            // System
            VmClassImpl systemClass = bootstrapClassLoader.loadClass("java/lang/System");

            systemClass.registerInvokable("nanoTime", (thread, target, args) -> Long.valueOf(System.nanoTime()));
            systemClass.registerInvokable("currentTimeMillis", (thread, target, args) -> Long.valueOf(System.currentTimeMillis()));
            systemClass.registerInvokable("initProperties", this::initProperties);

            //    private static native void initStackTraceElements(StackTraceElement[] elements,
            //                                                      Throwable x);

            // StackTraceElement
            VmClassImpl stackTraceElementClass = bootstrapClassLoader.loadClass("java/lang/StackTraceElement");

            stackTraceElementClass.registerInvokable("initStackTraceElements", (thread, target, args) -> {
                VmArrayImpl stackTrace = (VmArrayImpl) args.get(0);
                VmThrowableImpl throwable = (VmThrowableImpl) args.get(1);
                throwable.initStackTraceElements(stackTrace);
                return null;
            });

            // String
            VmClassImpl stringClass = bootstrapClassLoader.loadClass("java/lang/String");

            stringClass.registerInvokable("intern", (thread, target, args) -> intern((VmStringImpl) target));

            // Thread
            VmClassImpl threadNativeClass = bootstrapClassLoader.loadClass("java/lang/Thread");

            threadNativeClass.registerInvokable("yield", (thread, target, args) -> {
                Thread.yield();
                return null;
            });

            // Throwable
            VmClassImpl throwableClass = bootstrapClassLoader.loadClass("java/lang/Throwable");

            idx = throwableClass.getTypeDefinition().findSingleMethodIndex(me -> me.nameEquals("fillInStackTrace") && me.getParameters().size() == 1);
            threadNativeClass.registerInvokable(throwableClass.getTypeDefinition().getMethod(idx), (thread, target, args) -> {
                ((VmThrowableImpl)target).fillInStackTrace();
                return null;
            });

            // Now execute system initialization
            LoadedTypeDefinition systemType = systemClass.getTypeDefinition();
            // phase 1
            invokeExact(systemType.getMethod(systemType.findSingleMethodIndex(me -> me.nameEquals("initPhase1"))), null, List.of());
        }
    }

    private Object initProperties(final VmThread thread, final VmObject target, final List<Object> args) {
        VmObjectImpl props = (VmObjectImpl) args.get(0);
        URL propsResource = VmImpl.class.getClassLoader().getResource("system.properties");
        Properties properties = new Properties();
        if (propsResource != null) {
            try (InputStream is = propsResource.openStream()) {
                try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    try (BufferedReader br = new BufferedReader(isr)) {
                        properties.load(br);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Initial system properties could not be loaded");
            }
        } else {
            throw new IllegalStateException("Initial system properties could not be loaded");
        }
        // qbicc global fixed properties
        for (String name : properties.stringPropertyNames()) {
            setProperty(props, name, properties.getProperty(name));
        }
        // environment-specific properties
        Platform platform = ctxt.getPlatform();
        setProperty(props, "file.encoding", "UTF-8"); // only UTF-8
        setProperty(props, "file.separator", platform.getOs().getFileSeparator());

        // todo: java.class.path composed from command line
        setProperty(props, "java.home", System.getProperty("java.home")); // todo: spec must allow this to be undef
        setProperty(props, "java.io.tmpdir", System.getProperty("java.io.tmpdir")); // todo: reset at run time
        setProperty(props, "java.library.path", ""); // todo: JNI
        setProperty(props, "line.separator", platform.getOs().getLineSeparator());
        setProperty(props, "os.name", platform.getOs().getName());
        setProperty(props, "os.arch", platform.getCpu().getName());
        // todo: os.version
        setProperty(props, "path.separator", platform.getOs().getPathSeparator());

        setProperty(props, "user.country", Locale.getDefault().getCountry()); // todo: user-set locale on command line
        // todo: user.dir as a virtual directory, reset at run time
        // todo: user.home as a virtual directory, reset at run time
        setProperty(props, "user.language", Locale.getDefault().getLanguage()); // todo: user-set locale on command line
        // todo: user.name as temp user, reset at run time
        setProperty(props, "user.timezone", ""); // todo: reset at run time

        // these are non-spec but used by the JDK or other things
        setProperty(props, "sun.arch.data.model", String.valueOf(platform.getCpu().getCpuWordSize() << 3));
        // todo: sun.boot.library.path from command line
        setProperty(props, "sun.cpu.endian", ctxt.getTypeSystem().getEndianness() == ByteOrder.BIG_ENDIAN ? "big" : "little");
        setProperty(props, "sun.jnu.encoding", "UTF-8");

        return props;
    }

    void setProperty(VmObjectImpl properties, String key, String value) {
        invokeVirtual(setPropertyMethod, properties, List.of(intern(key), intern(value)));
    }

    public VmThread newThread(final String threadName, final VmObject threadGroup, final boolean daemon) {
        return new VmThreadImpl(threadClass, this);
    }

    public DefinedTypeDefinition loadClass(ClassContext classContext, final String name) throws Thrown {
        VmClass loaded = getClassLoaderForContext(classContext).loadClass(name);
        return loaded == null ? null : loaded.getTypeDefinition();
    }

    public VmObject allocateObject(final ClassObjectType type) {
        DefinedTypeDefinition def = type.getDefinition();
        ClassContext context = def.getContext();
        VmClassLoaderImpl classLoader = (VmClassLoaderImpl) context.getClassLoader();
        if (classLoader == null) {
            classLoader = getBootstrapClassLoader();
        }
        VmClassImpl vmClass = classLoader.loadClass(def.getInternalName());
        return vmClass.newInstance();
    }

    public VmByteArrayImpl allocateArray(byte[] bytes) {
        return new VmByteArrayImpl(this, bytes);
    }

    public void invokeExact(final ConstructorElement method, final VmObject instance, final List<Object> args) {
        getInstanceInvoker(method).invokeVoid(Vm.requireCurrentThread(), instance, args);
    }

    public Object invokeExact(final MethodElement method, final VmObject instance, final List<Object> args) {
        return getInstanceInvoker(method).invokeAny(Vm.requireCurrentThread(), instance, args);
    }

    public Object invokeVirtual(final MethodElement method, final VmObject instance, final List<Object> args) {
        return getInstanceInvoker(method).invokeAny(Vm.requireCurrentThread(), instance, args);
    }

    public void initialize(final VmClass vmClass) {
        VmThreadImpl vmThread = (VmThreadImpl) Vm.requireCurrentThread();
        ((VmClassImpl)vmClass).initialize(vmThread);
    }

    public void deliverSignal(final Signal signal) {

    }

    public VmObject allocateDirectBuffer(final ByteBuffer backingBuffer) {
        return null;
    }

    public DefinedTypeDefinition.Builder newTypeDefinitionBuilder(final VmClassLoader classLoader) {
        return classLoader.getClassContext().newTypeBuilder();
    }

    public VmObject getMainThreadGroup() {
        return null;
    }

    @Override
    public MemoryImpl allocate(int size) {
        if (size == 0) {
            return emptyMemory;
        } else if (wideRefs) {
            return new Memory64Impl(size);
        } else {
            return new Memory32Impl(size);
        }
    }

    @Override
    public VmClassLoaderImpl getClassLoaderForContext(ClassContext classContext) {
        VmClassLoaderImpl classLoader = (VmClassLoaderImpl) classContext.getClassLoader();
        return classLoader == null ? bootstrapClassLoader : classLoader;
    }

    @Override
    public void registerInvokable(ExecutableElement element, VmInvokable invokable) {
        DefinedTypeDefinition enclosingType = element.getEnclosingType();
        ClassContext classContext = enclosingType.getContext();
        VmClassLoaderImpl loader = getClassLoaderForContext(classContext);
        VmClassImpl vmClass = loader.getOrDefineClass(enclosingType.load());
        vmClass.registerInvokable(element, invokable);
    }

    VmStringImpl intern(VmStringImpl vmString) {
        return intern(vmString.getContent());
    }

    public VmStringImpl intern(String string) {
        VmStringImpl vmString = interned.get(string);
        if (vmString == null) {
            vmString = new VmStringImpl(this, stringClass, string);
            VmStringImpl appearing = interned.putIfAbsent(string, vmString);
            if (appearing != null) {
                vmString = appearing;
            }
        }
        return vmString;
    }

    public static VmImpl create(CompilationContext ctxt) {
        return new VmImpl(Assert.checkNotNullParam("ctxt", ctxt));
    }

    static VmImpl require() {
        return (VmImpl) Vm.requireCurrent();
    }

    private VmInvokable getInstanceInvoker(ExecutableElement element) {
        DefinedTypeDefinition enclosingType = element.getEnclosingType();
        VmClassLoaderImpl classLoader = getClassLoaderForContext(enclosingType.getContext());
        VmClassImpl loadedClass = classLoader.loadClass(enclosingType.getInternalName());
        return loadedClass.getOrCompile(element);
    }

    public Memory getGlobal(final GlobalVariableElement variableElement) {
        Memory64Impl memory = globals.get(variableElement);
        if (memory == null) {
            memory = new Memory64Impl((int) variableElement.getType().getSize());
            Memory64Impl appearing = globals.putIfAbsent(variableElement, memory);
            if (appearing != null) {
                memory = appearing;
            }
        }
        return memory;
    }
}
