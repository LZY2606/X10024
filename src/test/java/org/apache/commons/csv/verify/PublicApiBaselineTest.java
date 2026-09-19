/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.commons.csv.verify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Compares the public API of {@code org.apache.commons.csv}, collected by reflection from {@code target/classes},
 * against the checked-in baseline {@code src/test/resources/org/apache/commons/csv/verify/public-api-baseline.txt}.
 *
 * <p>
 * The comparison is strict in both directions: a removed or changed member breaks binary compatibility, and an added
 * member means the checked-in baseline is stale. Regenerate the baseline with
 * {@code mvn test -Dtest=PublicApiBaselineTest -Dapi.baseline.write=true} and commit the result.
 * </p>
 *
 * <p>
 * Collecting zero classes is a hard failure: an empty collection would otherwise compare equal to an empty baseline
 * and let a broken build pass silently.
 * </p>
 */
class PublicApiBaselineTest {

    private static final String BASELINE_RESOURCE = "/org/apache/commons/csv/verify/public-api-baseline.txt";

    private static final Path BASELINE_FILE = Paths.get("src", "test", "resources", "org", "apache", "commons", "csv", "build",
            "public-api-baseline.txt");

    private static final Path PACKAGE_DIR = Paths.get("target", "classes", "org", "apache", "commons", "csv");

    private static final String BASELINE_HEADER = String.join("\n",
            "# Licensed to the Apache Software Foundation (ASF) under one",
            "# or more contributor license agreements.  See the NOTICE file",
            "# distributed with this work for additional information",
            "# regarding copyright ownership.  The ASF licenses this file",
            "# to you under the Apache License, Version 2.0 (the",
            "# \"License\"); you may not use this file except in compliance",
            "# with the License.  You may obtain a copy of the License at",
            "#",
            "#   https://www.apache.org/licenses/LICENSE-2.0",
            "#",
            "# Unless required by applicable law or agreed to in writing,",
            "# software distributed under the License is distributed on an",
            "# \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY",
            "# KIND, either express or implied.  See the License for the",
            "# specific language governing permissions and limitations",
            "# under the License.",
            "#",
            "# Public API baseline of org.apache.commons.csv. Regenerate with:",
            "# mvn test -Dtest=PublicApiBaselineTest -Dapi.baseline.write=true",
            "");

    private static TreeSet<String> collectPublicApi() throws IOException {
        if (!Files.isDirectory(PACKAGE_DIR)) {
            throw new IllegalStateException(PACKAGE_DIR.toAbsolutePath() + " does not exist; run 'mvn package' so classes are compiled");
        }
        final List<Path> classFiles;
        try (Stream<Path> stream = Files.list(PACKAGE_DIR)) {
            classFiles = stream.filter(path -> {
                final String name = path.getFileName().toString();
                return name.endsWith(".class") && !name.equals("module-info.class");
            }).sorted().collect(Collectors.toList());
        }
        final TreeSet<String> lines = new TreeSet<>();
        final ClassLoader loader = PublicApiBaselineTest.class.getClassLoader();
        for (final Path classFile : classFiles) {
            final String simpleName = classFile.getFileName().toString().replace(".class", "");
            final Class<?> type;
            try {
                type = Class.forName("org.apache.commons.csv." + simpleName, false, loader);
            } catch (final ClassNotFoundException e) {
                throw new IllegalStateException("compiled class not on the test classpath: " + simpleName, e);
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            lines.add(describe(type));
            for (final Field field : type.getDeclaredFields()) {
                if (isApiMember(field.getModifiers()) && !field.isSynthetic()) {
                    lines.add("field " + Modifier.toString(field.getModifiers()) + " " + field.getType().getCanonicalName() + " " +
                            type.getCanonicalName() + "." + field.getName());
                }
            }
            for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (isApiMember(constructor.getModifiers()) && !constructor.isSynthetic()) {
                    lines.add("constructor " + Modifier.toString(constructor.getModifiers()) + " " + type.getCanonicalName() +
                            parameters(constructor.getParameterTypes()) + exceptions(constructor.getExceptionTypes()));
                }
            }
            for (final Method method : type.getDeclaredMethods()) {
                if (isApiMember(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                    lines.add("method " + Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getCanonicalName() +
                            " " + type.getCanonicalName() + "." + method.getName() + parameters(method.getParameterTypes()) +
                            exceptions(method.getExceptionTypes()));
                }
            }
        }
        return lines;
    }

    private static String describe(final Class<?> type) {
        final StringBuilder line = new StringBuilder("class ").append(Modifier.toString(type.getModifiers())).append(' ')
                .append(type.getCanonicalName());
        final Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            line.append(" extends ").append(superclass.getCanonicalName());
        }
        final List<String> interfaces = new ArrayList<>();
        for (final Class<?> implemented : type.getInterfaces()) {
            interfaces.add(implemented.getCanonicalName());
        }
        if (!interfaces.isEmpty()) {
            line.append(" implements ").append(interfaces.stream().sorted().collect(Collectors.joining(", ")));
        }
        return line.toString();
    }

    private static String exceptions(final Class<?>[] exceptionTypes) {
        if (exceptionTypes.length == 0) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (final Class<?> exceptionType : exceptionTypes) {
            names.add(exceptionType.getCanonicalName());
        }
        return " throws " + names.stream().sorted().collect(Collectors.joining(", "));
    }

    private static boolean isApiMember(final int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String parameters(final Class<?>[] parameterTypes) {
        final List<String> names = new ArrayList<>();
        for (final Class<?> parameterType : parameterTypes) {
            names.add(parameterType.getCanonicalName());
        }
        return "(" + String.join(", ", names) + ")";
    }

    private static TreeSet<String> readBaseline() {
        final TreeSet<String> lines = new TreeSet<>();
        try (InputStream input = PublicApiBaselineTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("baseline resource " + BASELINE_RESOURCE + " is missing from the test classpath");
            }
            final String content = new String(readAll(input), StandardCharsets.UTF_8);
            for (final String line : content.split("\\R")) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read baseline resource " + BASELINE_RESOURCE, e);
        }
        return lines;
    }

    private static byte[] readAll(final InputStream input) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    @Test
    void publicApiMatchesBaseline() throws Exception {
        final TreeSet<String> actual = collectPublicApi();
        assertFalse(actual.isEmpty(), () -> "collected zero public API classes from " + PACKAGE_DIR.toAbsolutePath() +
                "; refusing to compare an empty collection");
        if (Boolean.getBoolean("api.baseline.write")) {
            Files.write(BASELINE_FILE,
                    actual.stream().collect(Collectors.joining("\n", BASELINE_HEADER, "\n")).getBytes(StandardCharsets.UTF_8));
            fail("baseline rewritten to " + BASELINE_FILE.toAbsolutePath() + " (" + actual.size() +
                    " entries); review the diff, commit it, and re-run");
        }
        final TreeSet<String> baseline = readBaseline();
        assertFalse(baseline.isEmpty(), () -> "baseline " + BASELINE_FILE + " contains no API entries");
        final TreeSet<String> removed = new TreeSet<>(baseline);
        removed.removeAll(actual);
        final TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(baseline);
        assertTrue(removed.isEmpty() && added.isEmpty(), () -> "public API differs from " + BASELINE_FILE +
                "\nremoved/changed (binary incompatible):\n" + format(removed) +
                "\nadded (stale baseline, regenerate with -Dapi.baseline.write=true):\n" + format(added));
    }

    private static String format(final TreeSet<String> lines) {
        return lines.isEmpty() ? "  (none)" : lines.stream().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }
}
