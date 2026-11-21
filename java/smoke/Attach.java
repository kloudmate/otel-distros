import com.sun.tools.attach.VirtualMachine;

/** Loads a Java agent into a running JVM, as the KloudMate Agent does with jattach: Attach <pid> <jar> [args]. */
public class Attach {
  public static void main(String[] a) throws Exception {
    VirtualMachine vm = VirtualMachine.attach(a[0]);
    try {
      vm.loadAgent(a[1], a.length > 2 ? a[2] : null);
    } finally {
      vm.detach();
    }
  }
}
