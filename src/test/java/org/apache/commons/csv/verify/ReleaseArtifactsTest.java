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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Verifies the required and forbidden contents of the three distribution packages produced by {@code mvn package}:
 * the main jar, the sources jar and the tests jar.
 *
 * <p>
 * These tests intentionally fail instead of skipping when the artifacts are missing or empty: a skipped verification
 * would let a broken release pass silently.
 * </p>
 */
class ReleaseArtifactsTest {

    private static final String AUTOMATIC_MODULE_NAME = "org.apache.commons.csv";

    private static final String MODULE_INFO_ENTRY = "META-INF/versions/9/module-info.class";

    private static void assertContains(final List<String> entries, final String required) {
        assertTrue(entries.contains(required), () -> "missing required entry '" + required + "'; entries: " + abbreviate(entries));
    }

    private static void assertAbsent(final List<String> entries, final String description, final java.util.function.Predicate<String> forbidden) {
        entries.stream().filter(forbidden).forEach(entry -> fail("forbidden entry (" + description + "): " + entry));
    }

    private static String abbreviate(final List<String> entries) {
        return entries.size() <= 20 ? entries.toString() : entries.subList(0, 20) + "... (" + entries.size() + " total)";
    }

    private static boolean isJava9Plus() {
        final String spec = System.getProperty("java.specification.version", "1.8");
        return !spec.startsWith("1.");
    }

    @Test
    void mainJarContainsRequiredEntries() throws Exception {
        final Path jar = BuildArtifacts.mainJar();
        assertTrue(Files.size(jar) > 0, () -> jar + " is empty");
        final List<String> entries = BuildArtifacts.entries(jar);
        assertFalse(entries.isEmpty(), () -> jar + " contains no entries");
        assertContains(entries, "META-INF/MANIFEST.MF");
        assertContains(entries, "META-INF/LICENSE.txt");
        assertContains(entries, "META-INF/NOTICE.txt");
        assertContains(entries, MODULE_INFO_ENTRY);
        assertContains(entries, "org/apache/commons/csv/CSVFormat.class");
    }

    @Test
    void mainJarContainsNoForbiddenEntries() {
        final List<String> entries = BuildArtifacts.entries(BuildArtifacts.mainJar());
        assertAbsent(entries, "compiled test class", name -> name.endsWith("Test.class"));
        assertAbsent(entries, "benchmark class", name -> name.contains("Benchmark"));
        assertAbsent(entries, "test fixture package", name -> name.startsWith("org/apache/commons/csv/issues/"));
        assertAbsent(entries, "performance test package", name -> name.startsWith("org/apache/commons/csv/perf/"));
        assertAbsent(entries, "java source", name -> name.endsWith(".java"));
    }

    @Test
    void mainJarManifestDeclaresAutomaticModuleName() {
        final Path jar = BuildArtifacts.mainJar();
        final String automaticModuleName = BuildArtifacts.manifestValue(jar, "Automatic-Module-Name");
        assertEquals(AUTOMATIC_MODULE_NAME, automaticModuleName,
                () -> "Automatic-Module-Name mismatch in " + jar + " META-INF/MANIFEST.MF");
    }

    @Test
    void moduleDescriptorMatchesAutomaticModuleName() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(isJava9Plus(),
                "JPMS descriptors cannot be loaded on a Java 8 runtime; covered by the JDK 9+ matrix legs");
        final Path jar = BuildArtifacts.mainJar();
        final byte[] moduleInfo = BuildArtifacts.entryBytes(jar, MODULE_INFO_ENTRY);
        assertNotNull(moduleInfo, () -> jar + " is missing " + MODULE_INFO_ENTRY);
        // Reflective access keeps the test compilable with --release 8 while still exercising JPMS on JDK 9+.
        final Class<?> moduleDescriptorType = Class.forName("java.lang.module.ModuleDescriptor");
        final Object descriptor = moduleDescriptorType.getMethod("read", ByteBuffer.class).invoke(null, ByteBuffer.wrap(moduleInfo));
        final String moduleName = (String) moduleDescriptorType.getMethod("name").invoke(descriptor);
        assertEquals(BuildArtifacts.manifestValue(jar, "Automatic-Module-Name"), moduleName,
                () -> "module-info.class name disagrees with Automatic-Module-Name in " + jar);
        final Set<?> exports = (Set<?>) moduleDescriptorType.getMethod("exports").invoke(descriptor);
        boolean exportsPackage = false;
        for (final Object export : exports) {
            exportsPackage |= AUTOMATIC_MODULE_NAME.equals(export.getClass().getMethod("source").invoke(export));
        }
        assertTrue(exportsPackage, () -> "module " + moduleName + " does not export package " + AUTOMATIC_MODULE_NAME + ": " + exports);
    }

    @Test
    void sourcesJarContainsSourcesAndNoClassFiles() {
        final Path jar = BuildArtifacts.sourcesJar();
        final List<String> entries = BuildArtifacts.entries(jar);
        assertFalse(entries.isEmpty(), () -> jar + " contains no entries");
        assertContains(entries, "org/apache/commons/csv/CSVFormat.java");
        assertTrue(entries.stream().anyMatch(name -> name.endsWith(".java")), () -> jar + " contains no .java sources");
        assertAbsent(entries, "compiled class in sources jar", name -> name.endsWith(".class"));
    }

    @Test
    void testsJarContainsTestClassesAndNoMainClasses() {
        final Path jar = BuildArtifacts.testsJar();
        final List<String> entries = BuildArtifacts.entries(jar);
        assertFalse(entries.isEmpty(), () -> jar + " contains no entries");
        assertContains(entries, "org/apache/commons/csv/CSVFormatTest.class");
        assertAbsent(entries, "main class leaked into tests jar", name -> name.equals("org/apache/commons/csv/CSVFormat.class"));
        assertAbsent(entries, "java source in tests jar", name -> name.endsWith(".java"));
    }
}
