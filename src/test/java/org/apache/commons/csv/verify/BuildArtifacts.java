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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Locates the distribution artifacts produced by {@code mvn package} in {@code target/}.
 *
 * <p>
 * Every lookup fails with a diagnostic message instead of silently skipping: a missing or ambiguous artifact means the
 * verification ran against an incomplete or stale build, which must be a non-zero exit, never a green build.
 * </p>
 */
final class BuildArtifacts {

    static final Path TARGET = Paths.get("target");

    static Path mainJar() {
        return findJar("main jar", name -> !name.endsWith("-sources.jar") && !name.endsWith("-test-sources.jar") &&
                !name.endsWith("-tests.jar") && !name.endsWith("-javadoc.jar"));
    }

    static Path sourcesJar() {
        return findJar("sources jar", name -> name.endsWith("-sources.jar") && !name.endsWith("-test-sources.jar"));
    }

    static Path testsJar() {
        return findJar("tests jar", name -> name.endsWith("-tests.jar"));
    }

    private static Path findJar(final String description, final Predicate<String> matcher) {
        if (!Files.isDirectory(TARGET)) {
            throw new IllegalStateException(description + ": directory " + TARGET.toAbsolutePath() +
                    " does not exist; run 'mvn -DskipTests package' before 'mvn test' so the distribution artifacts exist");
        }
        final List<Path> matches;
        try (Stream<Path> stream = Files.list(TARGET)) {
            matches = stream.filter(path -> {
                final String name = path.getFileName().toString();
                return name.endsWith(".jar") && matcher.test(name);
            }).sorted().collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException(description + ": cannot list " + TARGET.toAbsolutePath(), e);
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException(description + ": no matching jar in " + TARGET.toAbsolutePath() +
                    "; run 'mvn -DskipTests package' before 'mvn test' so the distribution artifacts exist");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    description + ": ambiguous matches in " + TARGET.toAbsolutePath() + ": " + matches + "; run 'mvn clean' first");
        }
        return matches.get(0);
    }

    static List<String> entries(final Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.stream().map(java.util.jar.JarEntry::getName).sorted().collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read jar " + jar, e);
        }
    }

    static byte[] entryBytes(final Path jar, final String entryName) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            final java.util.jar.JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (java.io.InputStream input = jarFile.getInputStream(entry)) {
                final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read entry " + entryName + " from " + jar, e);
        }
    }

    static String manifestValue(final Path jar, final String attribute) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            final java.util.jar.Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                throw new IllegalStateException(jar + " has no META-INF/MANIFEST.MF");
            }
            return manifest.getMainAttributes().getValue(attribute);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read manifest from " + jar, e);
        }
    }

    private BuildArtifacts() {
        // no instances
    }
}
