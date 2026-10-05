package com.aisolutions.hrmanagement.dto;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resources return {@code Uni<Response>}, so Quarkus cannot see the DTOs inside the
 * {@code Response} and a native image would serialize them empty unless each one is
 * registered for reflection. This guard fails the build when a DTO class is missed.
 */
class DTONativeHintsTest {

    private static final String PACKAGE = "com.aisolutions.hrmanagement.dto";

    @Test
    void everyDtoIsRegisteredForNativeJsonSerialization() throws IOException, URISyntaxException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Path root = Path.of(loader.getResource(PACKAGE.replace('.', '/')).toURI());
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> classNames.add(toClassName(root, path)));
        }

        List<String> missing = new ArrayList<>();
        for (String className : classNames) {
            if (isGeneratedOrHelper(className)) {
                continue;
            }
            Class<?> type = loadClass(loader, className);
            if (type.isInterface() || type.isAnnotation() || type.isSynthetic()) {
                continue;
            }
            if (!type.isAnnotationPresent(RegisterForReflection.class)) {
                missing.add(className);
            }
        }

        assertFalse(classNames.isEmpty(), "no DTO classes found under " + PACKAGE);
        assertTrue(missing.isEmpty(), "Add @RegisterForReflection to: " + missing);
    }

    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        return PACKAGE + "." + relative.substring(0, relative.length() - ".class".length());
    }

    /** Anonymous classes, Lombok builders and test classes are never (de)serialized on their own. */
    private static boolean isGeneratedOrHelper(String className) {
        int marker = className.lastIndexOf('$');
        boolean anonymous = marker >= 0 && Character.isDigit(className.charAt(marker + 1));
        return anonymous || className.endsWith("Builder") || className.endsWith("Test");
    }

    private static Class<?> loadClass(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
