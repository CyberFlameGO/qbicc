package cc.quarkus.qcc.graph;

import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

abstract class NativeObjectTypeImpl extends NodeImpl implements NativeObjectType {
    private static final VarHandle arrayClassTypeHandle = ConstantBootstraps.fieldVarHandle(MethodHandles.lookup(), "arrayClassType", VarHandle.class, NativeObjectTypeImpl.class, ArrayClassType.class);

    private volatile ArrayClassType arrayClassType;

    public ArrayClassType getArrayClassType() {
        ArrayClassType value;
        value = this.arrayClassType;
        if (value != null) {
            return value;
        }
        ArrayClassType newVal = new ArrayClassTypeImpl(this);
        while (! arrayClassTypeHandle.compareAndSet(this, null, newVal)) {
            value = this.arrayClassType;
            if (value != null) {
                return value;
            }
        }
        return newVal;
    }
}
