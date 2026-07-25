package cn.ksmcbrigade.aiwiki_aca.util.agent;

import cn.ksmcbrigade.aiwiki_aca.util.agent.compile.SelfForwardingJavaFileManager;
import com.google.gson.JsonObject;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.tools.agent.MixinAgent;

import javax.tools.*;
import java.io.*;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;

public class CompilerUtils {

    public static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
    public static final Logger LOGGER = LoggerFactory.getLogger(CompilerUtils.class);

    static {
        if (COMPILER == null) {
            throw new RuntimeException("Failed to init JavaCompiler, The system java compiler is null.");
        }
    }

    public static String getSource(String clazzStr) throws ClassNotFoundException, UnmodifiableClassException, IOException {
        return getSource(Class.forName(clazzStr.replace("/", ".")));
    }

    public static String getSource(Class<?> clazz) throws UnmodifiableClassException, IOException {
        byte[] bytes = InstUtils.getClassBytes(Objects.requireNonNull(InstUtils.getInst()), clazz);
        return decompile(clazz.getName(), bytes);
    }

    private static String decompile(String className, byte[] classBytes) throws IOException {
        LOGGER.debug("Decompiling class '{}', bytecode size: {} bytes", className, classBytes.length);

        Map<String, String> resultMap = new HashMap<>();
        IResultSaver saver = new IResultSaver() {
            @Override
            public void saveClassFile(String path, String qualifiedName, String entryName,
                                      String content, int[] mapping) {
                resultMap.put(qualifiedName, content);
            }
            @Override public void saveFolder(String path) {}
            @Override public void copyFile(String source, String path, String entryName) {}
            @Override public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                                 String entryName, String content) {}
            @Override public void saveDirEntry(String path, String archiveName, String entryName) {}
            @Override public void createArchive(String path, String archiveName, Manifest manifest) {}
            @Override public void closeArchive(String path, String archiveName) {}
            @Override public void copyEntry(String source, String path, String archiveName, String entry) {}
        };

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("vineflower");
            Path classFile = tmpDir.resolve(className.replace('.', '/') + ".class");
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, classBytes);

            Map<String, Object> options = new HashMap<>();
            options.put("ignore-module-info", "1");
            options.put(IFernflowerPreferences.INDENT_STRING, "    ");

            Fernflower engine = new Fernflower(saver, options, IFernflowerLogger.NO_OP);

            for (String pathEntry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                engine.addLibrary(new File(pathEntry));
            }

            engine.addSource(classFile.toFile());
            engine.decompileContext();

            className = className.replace(".","/");
            String result = resultMap.get(className);
            if (result == null) {
                String simpleName = className.substring(className.lastIndexOf('/') + 1);
                for (Map.Entry<String, String> entry : resultMap.entrySet()) {
                    if (entry.getKey().endsWith("/" + simpleName)) {
                        return entry.getValue();
                    }
                }
            }
            if (result == null) {
                LOGGER.warn("No decompiled output produced for class '{}'", className);
                return "// no sources were generated";
            }
            return result;
        } finally {
            if (tmpDir != null) {
                try {
                    Files.walk(tmpDir)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                } catch (IOException ignored) {
                    LOGGER.debug("Failed to clean temp directory", ignored);
                }
            }
        }
    }

    public static CompileInfo compile(String... sources) {
        StringWriter writer = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = COMPILER.getStandardFileManager(null, null, null)) {
            JavaFileObject[] units = new JavaFileObject[sources.length];
            for (int i = 0; i < sources.length; i++) {
                String source = sources[i];
                units[i] = new SimpleJavaFileObject(
                        URI.create("string:///source" + i + JavaFileObject.Kind.SOURCE.extension),
                        JavaFileObject.Kind.SOURCE
                ) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source;
                    }
                };
            }
            Iterable<? extends JavaFileObject> compilationUnits = Arrays.asList(units);
            Iterable<String> options = List.of("-proc:none");
            JavaCompiler.CompilationTask task = COMPILER.getTask(
                    writer,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits
            );
            task.setProcessors(Collections.emptyList());
            boolean success = task.call();
            String info = buildDiagnosticMessage(diagnostics, writer.toString());
            return new CompileInfo(success, info);
        } catch (Exception e) {
            e.printStackTrace(new PrintWriter(writer));
            return new CompileInfo(false, writer.toString());
        }
    }

    public static SingleCompileInfo compileSingle(String className,String source) {
        StringWriter writer = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputStreamMap = new HashMap<>();
        try (JavaFileManager fileManager = new SelfForwardingJavaFileManager(outputStreamMap,COMPILER.getStandardFileManager(null, null, null))) {
            JavaFileObject[] units = new JavaFileObject[1];
            units[0] = new SimpleJavaFileObject(
                        URI.create("string:///source" + className.replace(".","/") + JavaFileObject.Kind.SOURCE.extension),
                        JavaFileObject.Kind.SOURCE
            ) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            Iterable<? extends JavaFileObject> compilationUnits = Arrays.asList(units);
            Iterable<String> options = List.of("-proc:none");
            JavaCompiler.CompilationTask task = COMPILER.getTask(
                    writer,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits
            );
            task.setProcessors(Collections.emptyList());
            boolean success = task.call();
            String info = buildDiagnosticMessage(diagnostics, writer.toString());
            ByteArrayOutputStream byteArrayOutputStream = null;
            byteArrayOutputStream = outputStreamMap.getOrDefault(className,null);
            if(byteArrayOutputStream==null) byteArrayOutputStream = outputStreamMap.getOrDefault(className.replace(".","/"),null);
            return new SingleCompileInfo(success, info,byteArrayOutputStream==null?MixinAgent.ERROR_BYTECODE:byteArrayOutputStream.toByteArray());
        } catch (Exception e) {
            e.printStackTrace(new PrintWriter(writer));
            return new SingleCompileInfo(false, writer.toString(), MixinAgent.ERROR_BYTECODE);
        }
    }

    public static Class<?> defineClass(Class<?> targetClass,byte[] bytes) throws IllegalAccessException {
        if (UnsafeUtils.lookup != null) {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(targetClass,UnsafeUtils.lookup);
            return lookup.defineClass(bytes);
        }
        throw new RuntimeException("Failed to get lookup.");
    }

    public static Class<?> compileSingleSourceIntoClass(String clazzName,String source,Class<?> targetClass) throws IllegalAccessException {
        CompilerUtils.SingleCompileInfo info = CompilerUtils.compileSingle(clazzName,source);
        if(!info.success) throw new RuntimeException("Failed to compile "+clazzName);
        if(Arrays.equals(info.bytes, MixinAgent.ERROR_BYTECODE)) throw new RuntimeException("The class bytes is error");
        return defineClass(targetClass, info.bytes);
    }

    public static Object compileSingleSourceIntoClassInstanceWithoutArgs(String clazzName,String source,Class<?> targetClass) throws Throwable {
        if(UnsafeUtils.lookup==null) throw new RuntimeException("Failed to get lookup");
        Class<?> clazz = compileSingleSourceIntoClass(clazzName,source,targetClass);
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static String buildDiagnosticMessage(DiagnosticCollector<JavaFileObject> diagnostics, String extraOutput) {
        StringBuilder sb = new StringBuilder();
        if (!extraOutput.isEmpty()) {
            sb.append("Compiler output:\n").append(extraOutput).append("\n");
        }
        if (!diagnostics.getDiagnostics().isEmpty()) {
            sb.append("Diagnostics:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append(String.format("[%s] line %d: %s%n",
                        d.getKind(), d.getLineNumber(), d.getMessage(null)));
            }
        }
        return sb.toString();
    }

    public record CompileInfo(boolean success, String info) { }

    public record SingleCompileInfo(boolean success, String info,byte[] bytes) { }
}