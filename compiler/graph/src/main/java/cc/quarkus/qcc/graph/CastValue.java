package cc.quarkus.qcc.graph;

/**
 *
 */
public interface CastValue extends Value {
    Value getInput();
    void setInput(Value value);

    Type getType();
    void setType(Type targetType);

    default int getValueDependencyCount() {
        return 1;
    }

    default Value getValueDependency(int index) throws IndexOutOfBoundsException {
        return index == 0 ? getInput() : Util.throwIndexOutOfBounds(index);
    }
}
