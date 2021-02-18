import static cc.quarkus.qcc.runtime.CNative.*;

public class InvokeVirtual {
    @extern
    public static native int putchar(int arg);

        int a;
        int b;

        InvokeVirtual(int x, int y) {
            this.a = x;
            this.b = y;
        }

        int sum() { return a + b; }
        int mul() { return a * b; }


    @export
    public static int main() {
        InvokeVirtual obj = new InvokeVirtual(10, 5);
        int s = obj.sum();
        int p = obj.mul();
        if (s == 15 && p == 50) {
            reportSuccess();
            return 0;
        } else {
            reportFailure(s, p);
            return 1;
        }
    }

    static void reportSuccess() {
        putchar('P');
        putchar('A');
        putchar('S');
        putchar('S');
        putchar('\n');
    }

    static void reportFailure(int s, int p) {
        putchar('F');
        putchar('A');
        putchar('I');
        putchar('L');
        putchar(':');
        // TODO: putchar of an int isn't actually that helpful...
        putchar(s);
        putchar(p);
        putchar('\n');
    }

    public static void main(String[] args) {
    }
}
