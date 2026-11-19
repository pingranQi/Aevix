import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Loads the packaged Java record in a disposable app_process. */
public final class RecordTagRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected merged framework DEX path");
        ClassLoader loader = new InMemoryDexClassLoader(
                ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))),
                ClassLoader.getSystemClassLoader());
        Class<?> record = Class.forName("org.aevix.recordtest.RecordProbe$Value", true, loader);
        Object first = record.getConstructor(int.class).newInstance(42);
        Object equal = record.getConstructor(int.class).newInstance(42);
        Object different = record.getConstructor(int.class).newInstance(43);
        if (!first.equals(equal) || first.equals(different)
                || first.hashCode() != equal.hashCode()
                || !Integer.valueOf(42).equals(record.getMethod("value").invoke(first))
                || !first.toString().contains("42")) {
            throw new AssertionError("Packaged record failed its value contract");
        }
        System.out.println("PASS: merged framework DEX loads and executes a Java record");
    }
}
