
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.TypeParameter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JavaChunker – splits Java source into chunks for RAG systems.
 *   • one “class-header” chunk per (possibly nested) class / interface
 *   • one chunk per method
 *   • one chunk per constructor
 *
 * Extra context for inner/nested classes:
 *   – header chunk starts with "// enclosing: Outer.Inner"
 *   – member chunks are wrapped in "class Outer.Inner { … }"
 *
 * Java 8 compatible.  Requires javaparser-core-3.26.4.jar (or any 3.x).
 */
public class JavaChunker {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: java JavaChunker <path-to-java-source-file>");
            return;
        }
        javaChunker(args[0]);
    }

    public static void javaChunker(String fileName) throws IOException {

        Path sourceFile = Paths.get(fileName);

        /* 1. Parse */
        CompilationUnit cu = StaticJavaParser.parse(sourceFile);

        /* 2. Shared package + imports */
        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");
        List<ImportDeclaration> imports = cu.getImports();

        /* 3. Iterate over every class / interface (nested ones included) */
        List<ClassOrInterfaceDeclaration> classes =
                cu.findAll(ClassOrInterfaceDeclaration.class);

        for (ClassOrInterfaceDeclaration clazz : classes) {

            /* 3a. Header chunk */
            processChunkForEmbedding(
                    buildClassHeaderChunk(packageName, imports, clazz));

            /* 3b. Method chunks */
            for (MethodDeclaration m : clazz.getMethods()) {
                processChunkForEmbedding(
                        buildMemberChunk(packageName, clazz, m.toString()));
            }

            /* 3c. Constructor chunks */
            for (ConstructorDeclaration ctor : clazz.getConstructors()) {
                processChunkForEmbedding(
                        buildMemberChunk(packageName, clazz, ctor.toString()));
            }
        }
    }

    /* ========== helper methods ========== */

    /** Build header chunk for one (possibly nested) class/interface. */
    private static String buildClassHeaderChunk(String packageName,
                                                List<ImportDeclaration> imports,
                                                ClassOrInterfaceDeclaration clazz) {

        StringBuilder sb = new StringBuilder();

        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }

        imports.forEach(imp -> sb.append(imp.toString()));
        if (!imports.isEmpty()) sb.append('\n');

        /* class-level Javadoc (if present) */
        clazz.getJavadocComment()
                .ifPresent(c -> sb.append(c.toString()).append('\n'));

        /* helpful nesting comment */
        sb.append("// enclosing: ").append(getQualifiedName(clazz)).append('\n');

        /* declaration line(s) */
        sb.append(buildDeclarationLine(clazz)).append(" {\n\n");

        /* fields */
        clazz.getFields().forEach(f -> sb.append(f.toString()).append('\n'));

        /* initializer blocks */
        clazz.getMembers().stream()
                .filter(m -> m instanceof InitializerDeclaration)
                .forEach(init -> sb.append(init.toString()).append('\n'));

        sb.append("}\n");
        return sb.toString();
    }

    /** Build a member chunk (method or constructor) with full class context. */
    private static String buildMemberChunk(String packageName,
                                           ClassOrInterfaceDeclaration clazz,
                                           String code) {
        StringBuilder sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("class ").append(getQualifiedName(clazz)).append(" {\n\n");
        sb.append(code).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    /** Qualified name like Outer.Inner.DeepInner */
    private static String getQualifiedName(ClassOrInterfaceDeclaration clazz) {
        StringBuilder q = new StringBuilder(clazz.getNameAsString());
        Node p = clazz.getParentNode().orElse(null);
        while (p instanceof ClassOrInterfaceDeclaration) {
            q.insert(0, ((ClassOrInterfaceDeclaration) p).getNameAsString() + ".");
            p = p.getParentNode().orElse(null);
        }
        return q.toString();
    }

    /** Build declaration line compatible with all JavaParser 3.x versions. */
    private static String buildDeclarationLine(ClassOrInterfaceDeclaration clazz) {
        StringBuilder line = new StringBuilder();
        clazz.getModifiers().forEach(m ->
                line.append(m.getKeyword().asString()).append(' '));
        line.append(clazz.isInterface() ? "interface " : "class ")
                .append(clazz.getNameAsString());
        if (!clazz.getTypeParameters().isEmpty()) {
            line.append('<')
                    .append(clazz.getTypeParameters().stream()
                            .map(TypeParameter::toString)
                            .collect(Collectors.joining(", ")))
                    .append('>');
        }
        if (!clazz.getExtendedTypes().isEmpty()) {
            line.append(" extends ")
                    .append(clazz.getExtendedTypes().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(", ")));
        }
        if (!clazz.getImplementedTypes().isEmpty()) {
            line.append(" implements ")
                    .append(clazz.getImplementedTypes().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(", ")));
        }
        return line.toString();
    }

    /** Stub: replace with your embedding/storage logic. */
    private static void processChunkForEmbedding(String chunk) {
        System.out.println("=== EMBEDDING CHUNK START ===");
        System.out.println(chunk);
        System.out.println("=== EMBEDDING CHUNK END ===\n");
    }
}
