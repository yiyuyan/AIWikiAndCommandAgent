package cn.ksmcbrigade.aiwiki_aca.util.agent;

import com.google.gson.JsonObject;

import javax.tools.*;
import java.io.*;
import java.util.Collections;
import java.util.List;

public class CompilerUtils {

    public static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    static {
        if(COMPILER==null){
            throw new RuntimeException("Failed to init JavaCompiler,The system java compiler is null.");
        }
    }

    public static CompileInfo compile(String... sources) {
        StringWriter writer = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = COMPILER.getStandardFileManager(null, null, null)) {

            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromStrings(List.of(sources));
            Iterable<String> options = List.of("-proc:none");

            JavaCompiler.CompilationTask task = COMPILER.getTask(
                    writer,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    units
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

    public record CompileInfo(boolean success,String info){

        public JsonObject toJson(){
            JsonObject object = new JsonObject();
            object.addProperty("success",success);
            object.addProperty("info",info);
            return object;
        }
    }
}
