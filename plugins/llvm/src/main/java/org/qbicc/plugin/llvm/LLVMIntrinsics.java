package org.qbicc.plugin.llvm;

import java.util.EnumSet;
import java.util.List;

import org.qbicc.context.ClassContext;
import org.qbicc.context.CompilationContext;
import org.qbicc.graph.AsmHandle;
import org.qbicc.graph.BasicBlockBuilder;
import org.qbicc.graph.BlockEarlyTermination;
import org.qbicc.graph.ClassOf;
import org.qbicc.graph.Load;
import org.qbicc.graph.NewArray;
import org.qbicc.graph.StaticMethodElementHandle;
import org.qbicc.graph.Value;
import org.qbicc.graph.ValueHandle;
import org.qbicc.graph.literal.IntegerLiteral;
import org.qbicc.graph.literal.Literal;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.graph.literal.ObjectLiteral;
import org.qbicc.graph.literal.StringLiteral;
import org.qbicc.graph.literal.TypeLiteral;
import org.qbicc.interpreter.VmString;
import org.qbicc.plugin.intrinsics.Intrinsics;
import org.qbicc.plugin.intrinsics.StaticIntrinsic;
import org.qbicc.type.FunctionType;
import org.qbicc.type.TypeSystem;
import org.qbicc.type.ValueType;
import org.qbicc.type.definition.DefinedTypeDefinition;
import org.qbicc.type.definition.element.ExecutableElement;
import org.qbicc.type.descriptor.ArrayTypeDescriptor;
import org.qbicc.type.descriptor.BaseTypeDescriptor;
import org.qbicc.type.descriptor.ClassTypeDescriptor;
import org.qbicc.type.descriptor.MethodDescriptor;

public final class LLVMIntrinsics {
    public static void register(CompilationContext ctxt) {
        Intrinsics intrinsics = Intrinsics.get(ctxt);
        ClassContext classContext = ctxt.getBootstrapClassContext();

        ClassTypeDescriptor buildTargetDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/Build$Target");

        MethodDescriptor emptyToBool = MethodDescriptor.synthesize(classContext, BaseTypeDescriptor.Z, List.of());

        StaticIntrinsic isLlvm = (builder, target, arguments) -> ctxt.getLiteralFactory().literalOf(true);
        intrinsics.registerIntrinsic(buildTargetDesc, "isLlvm", emptyToBool, isLlvm);

        // inline assembly
        ClassTypeDescriptor llvmRuntimeDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/llvm/LLVM");
        //    public static native <T extends object> T asm(Class<T> returnType, String instruction, String operands, int flags, object... args);

        ClassTypeDescriptor thingDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/CNative$object");
        ClassTypeDescriptor vaListDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/stdc/Stdarg$va_list");
        ClassTypeDescriptor vaListPtrDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/stdc/Stdarg$va_list_ptr");
        ClassTypeDescriptor classDesc = ClassTypeDescriptor.synthesize(classContext, "java/lang/Class");
        ClassTypeDescriptor stringDesc = ClassTypeDescriptor.synthesize(classContext, "java/lang/String");
        ArrayTypeDescriptor arrayOfThingDesc = ArrayTypeDescriptor.of(classContext, thingDesc);

        MethodDescriptor asmDesc = MethodDescriptor.synthesize(classContext,
            thingDesc,
            List.of(
                classDesc,
                stringDesc,
                stringDesc,
                BaseTypeDescriptor.I,
                arrayOfThingDesc
            )
        );
        MethodDescriptor vaListClassToThing = MethodDescriptor.synthesize(classContext, thingDesc, List.of(vaListDesc, classDesc));
        MethodDescriptor vaListToVoid = MethodDescriptor.synthesize(classContext, BaseTypeDescriptor.V, List.of(vaListDesc));
        MethodDescriptor vaListVaListToVoid = MethodDescriptor.synthesize(classContext, BaseTypeDescriptor.V, List.of(vaListDesc, vaListDesc));
        MethodDescriptor vaListPtrToVoid = MethodDescriptor.synthesize(classContext, BaseTypeDescriptor.V, List.of(vaListPtrDesc));
        MethodDescriptor vaListPtrVaListPtrToVoid = MethodDescriptor.synthesize(classContext, BaseTypeDescriptor.V, List.of(vaListPtrDesc, vaListPtrDesc));

        intrinsics.registerIntrinsic(llvmRuntimeDesc, "asm", asmDesc, LLVMIntrinsics::asm);

        // replace Stdarg methods with intrinsics; like the corresponding C macros, they take a va_list rather than a pointer to it

        Literal voidLiteral = ctxt.getLiteralFactory().zeroInitializerLiteralOfType(ctxt.getTypeSystem().getVoidType());

        ClassTypeDescriptor stdArgDesc = ClassTypeDescriptor.synthesize(classContext, "org/qbicc/runtime/stdc/Stdarg");

        StaticIntrinsic saVaStart = (builder, target, arguments) -> {
            BasicBlockBuilder fb = builder.getFirstBuilder();
            ValueHandle vaListHandle;
            Value vaList = arguments.get(0);
            if (vaList instanceof Load load) {
                vaListHandle = load.getValueHandle();
            } else {
                ctxt.error(builder.getLocation(), "Invalid ap argument to va_start: must have an address");
                return voidLiteral;
            }
            return fb.call(fb.staticMethod(llvmRuntimeDesc, "va_start", vaListPtrToVoid), List.of(fb.addressOf(vaListHandle)));
        };

        intrinsics.registerIntrinsic(stdArgDesc, "va_start", vaListToVoid, saVaStart);

        StaticIntrinsic saVaEnd = (builder, target, arguments) -> {
            BasicBlockBuilder fb = builder.getFirstBuilder();
            ValueHandle vaListHandle;
            Value vaList = arguments.get(0);
            if (vaList instanceof Load load) {
                vaListHandle = load.getValueHandle();
            } else {
                ctxt.error(builder.getLocation(), "Invalid ap argument to va_end: must have an address");
                return voidLiteral;
            }
            return fb.call(fb.staticMethod(llvmRuntimeDesc, "va_end", vaListPtrToVoid), List.of(fb.addressOf(vaListHandle)));
        };

        intrinsics.registerIntrinsic(stdArgDesc, "va_end", vaListToVoid, saVaEnd);

        StaticIntrinsic saVaCopy = (builder, target, arguments) -> {
            BasicBlockBuilder fb = builder.getFirstBuilder();
            ValueHandle destHandle;
            Value destList = arguments.get(0);
            if (destList instanceof Load load) {
                destHandle = load.getValueHandle();
            } else {
                ctxt.error(builder.getLocation(), "Invalid dest argument to va_copy: must have an address");
                return voidLiteral;
            }
            ValueHandle srcHandle;
            Value srcList = arguments.get(1);
            if (srcList instanceof Load load2) {
                srcHandle = load2.getValueHandle();
            } else {
                ctxt.error(builder.getLocation(), "Invalid src argument to va_copy: must have an address");
                return voidLiteral;
            }
            return fb.call(fb.staticMethod(llvmRuntimeDesc, "va_copy", vaListPtrVaListPtrToVoid), List.of(fb.addressOf(destHandle), fb.addressOf(srcHandle)));
        };

        intrinsics.registerIntrinsic(stdArgDesc, "va_copy", vaListVaListToVoid, saVaCopy);

        // this one is technically implementation-neutral, but we can keep it here until we have another backend
        StaticIntrinsic saVaArg = (builder, target, arguments) -> {
            BasicBlockBuilder fb = builder.getFirstBuilder();
            ValueHandle vaListHandle;
            Value vaList = arguments.get(0);
            if (vaList instanceof Load load) {
                vaListHandle = load.getValueHandle();
            } else {
                ctxt.error(builder.getLocation(), "Invalid ap argument to va_arg: must have an address");
                throw new BlockEarlyTermination(builder.unreachable());
            }
            Value outputType = arguments.get(1);
            if (outputType instanceof ClassOf co && co.getInput() instanceof TypeLiteral tl) {
                return builder.vaArg(fb.addressOf(vaListHandle), tl.getValue());
            } else {
                ctxt.error(builder.getLocation(), "Invalid type argument to va_arg (must be a class literal)");
                throw new BlockEarlyTermination(builder.unreachable());
            }
        };

        intrinsics.registerIntrinsic(stdArgDesc, "va_arg", vaListClassToThing, saVaArg);
    }

    // flag values must match the LLVM runtime API class.

    static final int ASM_FLAG_SIDE_EFFECT = 1 << 0;
    static final int ASM_FLAG_ALIGN_STACK = 1 << 1;
    static final int ASM_FLAG_INTEL_DIALECT = 1 << 2;
    static final int ASM_FLAG_UNWIND = 1 << 3;
    static final int ASM_FLAG_IMPLICIT_SIDE_EFFECT = 1 << 4;
    static final int ASM_FLAG_NO_RETURN = 1 << 5;

    /**
     * Handle an inline assembly statement.
     *
     * @param bb the block builder (must not be {@code null})
     * @param handle the input handle (must not be {@code null})
     * @param parameters the parameter values (must not be {@code null})
     * @return the assembly result (if any)
     */
    private static Value asm(final BasicBlockBuilder bb, final StaticMethodElementHandle handle, final List<Value> parameters) {
        ExecutableElement element = bb.getCurrentElement();
        DefinedTypeDefinition enclosingType = element.getEnclosingType();
        ClassContext classContext = enclosingType.getContext();
        CompilationContext ctxt = classContext.getCompilationContext();
        LiteralFactory lf = ctxt.getLiteralFactory();
        TypeSystem ts = ctxt.getTypeSystem();

        Value returnTypeClazzValue = parameters.get(0);
        Value instructionValue = parameters.get(1);
        Value operandsValue = parameters.get(2);
        Value flagsValue = parameters.get(3);
        Value argsValue = parameters.get(4);

        // Determine the actual return type.
        // todo: also support VmClass literals
        ValueType returnType;
        if (returnTypeClazzValue instanceof ClassOf co && co.getInput() instanceof TypeLiteral tl) {
            returnType = tl.getValue();
        } else {
            ctxt.error(bb.getLocation(), "Type argument to `asm` must be a class literal or constant value");
            return lf.zeroInitializerLiteralOfType(ts.getVoidType());
        }

        // Get the instruction string.
        String instruction;
        if (instructionValue instanceof StringLiteral sl) {
            instruction = sl.getValue();
        } else if (instructionValue instanceof ObjectLiteral ol && ol.getValue() instanceof VmString vs) {
            instruction = vs.getContent();
        } else {
            ctxt.error(bb.getLocation(), "Instruction argument to `asm` must be a string literal or constant value");
            return lf.zeroInitializerLiteralOfType(ts.getVoidType());
        }

        // Get the operands string.
        String operands;
        if (operandsValue instanceof StringLiteral sl) {
            operands = sl.getValue();
        } else if (operandsValue instanceof ObjectLiteral ol && ol.getValue() instanceof VmString vs) {
            operands = vs.getContent();
        } else {
            ctxt.error(bb.getLocation(), "Operands argument to `asm` must be a string literal or constant value");
            return lf.zeroInitializerLiteralOfType(ts.getVoidType());
        }

        // Get the flags value.
        int flags;
        if (flagsValue instanceof IntegerLiteral il) {
            flags = il.intValue();
        } else {
            ctxt.error(bb.getLocation(), "Flags argument to `asm` must be an integer literal or constant value");
            return lf.zeroInitializerLiteralOfType(ts.getVoidType());
        }

        // Arguments.
        int argCount;
        Value[] args;
        FunctionType type;
        // todo: we could alternatively get the array length and see if it's a literal.
        if (argsValue instanceof NewArray na) {
            if (na.getSize() instanceof IntegerLiteral szl) {
                argCount = szl.intValue();
                args = new Value[argCount];
                ValueType[] types = new ValueType[argCount];
                ValueHandle arrayHandle = bb.referenceHandle(argsValue);
                for (int i = 0; i < argCount; i ++) {
                    Value value = bb.load(bb.elementOf(arrayHandle, lf.literalOf(i)));
                    args[i] = value;
                    types[i] = value.getType();
                }
                type = ts.getFunctionType(returnType, types);
            } else {
                ctxt.error(bb.getLocation(), "Flags argument to `asm` must be an integer literal or constant value");
                return lf.zeroInitializerLiteralOfType(ts.getVoidType());
            }
        } else {
            ctxt.error(bb.getLocation(), "Arguments to `asm` must be an immediate new array creation");
            return lf.zeroInitializerLiteralOfType(ts.getVoidType());
        }

        EnumSet<AsmHandle.Flag> flagSet = EnumSet.of(AsmHandle.Flag.NO_THROW);
        boolean generalSideEffects = false;
        if ((flags & ASM_FLAG_SIDE_EFFECT) != 0) {
            flagSet.add(AsmHandle.Flag.SIDE_EFFECT);
            generalSideEffects = true;
        }
        if ((flags & ASM_FLAG_IMPLICIT_SIDE_EFFECT) != 0) {
            flagSet.add(AsmHandle.Flag.IMPLICIT_SIDE_EFFECT);
            generalSideEffects = true;
        }
        if ((flags & ASM_FLAG_ALIGN_STACK) != 0) {
            flagSet.add(AsmHandle.Flag.ALIGN_STACK);
        }
        if ((flags & ASM_FLAG_INTEL_DIALECT) != 0) {
            flagSet.add(AsmHandle.Flag.INTEL_DIALECT);
        }
        if ((flags & ASM_FLAG_UNWIND) != 0) {
            flagSet.remove(AsmHandle.Flag.NO_THROW);
            generalSideEffects = true;
        }
        boolean noReturn = (flags & ASM_FLAG_NO_RETURN) != 0;

        ValueHandle asm = bb.asm(instruction, operands, flagSet, type);

        if (noReturn) {
            throw new BlockEarlyTermination(bb.callNoReturn(asm, List.of(args)));
        }

        if (generalSideEffects) {
            return bb.call(asm, List.of(args));
        } else {
            return bb.callNoSideEffects(asm, List.of(args));
        }
    }
}
