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

package org.apache.commons.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the required and forbidden content of every published artifact:
 * the main (multi-release) jar, the sources jar, the test jar, and the
 * bin/src distribution assemblies.
 *
 * <p>These checks only run under the {@code release-verify} profile, i.e.
 * after {@code package} has produced the artifacts, as driven by
 * {@code verify.sh packages}. They never run in a plain {@code mvn test}
 * matrix build, where the artifacts legitimately do not exist yet.</p>
 */
public class ReleaseArtifactsIT {

    private static final String MODULE_NAME = "org.apache.commons.csv";

    private static final String PACKAGE_PATH = "org/apache/commons/csv/";

    private static final Set<String> EXPECTED_MAIN_CLASSES = new LinkedHashSet<>(Arrays.asList(
            "CSVException.class",
            "CSVFormat.class",
            "CSVParser.class",
            "CSVPrinter.class",
            "CSVRecord.class",
            "Constants.class",
            "DuplicateHeaderMode.class",
            "ExtendedBufferedReader.class",
            "Lexer.class",
            "QuoteMode.class",
            "Token.class",
            "package-info.class"));

    private static Path targetDir;
    private static Path mainJar;
    private static Path sourcesJar;
    private static Path testJar;
    private static Path binZip;
    private static Path srcZip;

    @BeforeAll
    public static void setUp() {
        assumeTrue(Boolean.getBoolean("csv.release.verify"),
                "Release artifact checks require -Prelease-verify (verify.sh packages)");
        targetDir = locateTarget();
        mainJar = requireArtifact(findJar(false, PACKAGE_PATH + "CSVFormat.class"), "main jar");
        sourcesJar = requireArtifact(
                findJar(false, PACKAGE_PATH + "CSVFormat.java"), "sources jar");
        testJar = requireArtifact(
                findJar(true, PACKAGE_PATH + "CSVParserTest.class"), "test jar");
        binZip = requireArtifact(findAssemblyZip("-bin.zip", "apidocs/index.html",
                PACKAGE_PATH + "CSVFormat"), "bin zip");
        srcZip = requireArtifact(findAssemblyZip("-src.zip",
                "src/main/java/org/apache/commons/csv/CSVFormat.java",
                "src/test/java/org/apache/commons/csv/CSVParserTest.java"), "src zip");
    }

    @Test
    public void testMainJarIsNonEmptyAndContainsAllPublishedClasses() throws IOException {
        final List<String> entries = readZipEntries(mainJar);
        assertFalse(entries.isEmpty(), () -> mainJar + " must not be empty");
        for (final String expectedClass : EXPECTED_MAIN_CLASSES) {
            assertTrue(entries.contains(PACKAGE_PATH + expectedClass),
                    () -> mainJar + " is missing required class " + PACKAGE_PATH +
                            expectedClass);
        }
    }

    @Test
    public void testMainJarDeclaresAutomaticModuleName() throws IOException {
        try (JarFile jarFile = new JarFile(mainJar.toFile())) {
            final String automaticModuleName = jarFile.getManifest().getMainAttributes()
                    .getValue("Automatic-Module-Name");
            assertEquals(MODULE_NAME, automaticModuleName,
                    () -> mainJar + " must declare Automatic-Module-Name: " +
                            MODULE_NAME);
            final String multiRelease = jarFile.getManifest().getMainAttributes()
                    .getValue("Multi-Release");
            assertEquals("true", multiRelease,
                    () -> mainJar + " must declare Multi-Release: true");
        }
    }

    @Test
    public void testMainJarModuleDescriptorMatchesRuntime() throws IOException {
        final String specVersion = System.getProperty("java.specification.version", "1.8");
        final int feature = specVersion.startsWith("1.") ?
                Integer.parseInt(specVersion.substring(2)) :
                Integer.parseInt(specVersion.split("\\.")[0]);
        final boolean moduleRuntime = feature >= 9;
        try (JarFile jarFile = new JarFile(mainJar.toFile())) {
            final JarEntry moduleInfo =
                    jarFile.getJarEntry("META-INF/versions/9/module-info.class");
            if (moduleRuntime) {
                assertNotNull(moduleInfo, () -> mainJar + " must contain " +
                        "META-INF/versions/9/module-info.class on a module-aware runtime");
            } else {
                assertTrue(moduleInfo == null,
                        () -> mainJar + " built on Java 8 must not carry a module descriptor");
            }
            assertTrue(jarFile.getJarEntry("module-info.class") == null,
                    () -> mainJar + " module descriptor must only live under " +
                            "META-INF/versions/9");
        }
    }

    @Test
    public void testMainJarContainsLicenseAndNotice() throws IOException {
        final List<String> entries = readZipEntries(mainJar);
        assertTrue(entries.contains("META-INF/LICENSE.txt"),
                () -> mainJar + " must contain META-INF/LICENSE.txt");
        assertTrue(entries.contains("META-INF/NOTICE.txt"),
                () -> mainJar + " must contain META-INF/NOTICE.txt");
    }

    @Test
    public void testSourcesJarContainsOnlyMainSources() throws IOException {
        final List<String> entries = readZipEntries(sourcesJar);
        assertFalse(entries.isEmpty(), () -> sourcesJar + " must not be empty");
        final Path mainSourceRoot = targetDir.resolve("../src/main/java").normalize();
        for (final String expectedEntry : javaSourceEntries(mainSourceRoot)) {
            assertTrue(entries.contains(expectedEntry),
                    () -> sourcesJar + " is missing required source entry " +
                            expectedEntry);
        }
        for (final String entry : entries) {
            if (entry.endsWith("/")) {
                continue;
            }
            assertTrue(entry.startsWith("META-INF/") || entry.endsWith(".java"),
                    () -> sourcesJar + " must contain only *.java and META-INF " +
                            "entries: " + entry);
            assertFalse(entry.startsWith(PACKAGE_PATH) &&
                    (entry.startsWith(PACKAGE_PATH + "issues/") ||
                            entry.startsWith(PACKAGE_PATH + "perf/")),
                    () -> sourcesJar + " must not leak test sources: " + entry);
        }
    }

    @Test
    public void testTestJarContainsCompiledTestsAndResources() throws IOException {
        final List<String> entries = readZipEntries(testJar);
        assertFalse(entries.isEmpty(), () -> testJar + " must not be empty");
        assertTrue(entries.contains(PACKAGE_PATH + "CSVParserTest.class"),
                () -> testJar + " must contain compiled test classes");
        assertTrue(entries.contains(PACKAGE_PATH + "CSVFileParser/test.csv"),
                () -> testJar + " must contain test resources");
        assertTrue(entries.contains("META-INF/LICENSE.txt"),
                () -> testJar + " must contain META-INF/LICENSE.txt");
        assertTrue(entries.contains("META-INF/NOTICE.txt"),
                () -> testJar + " must contain META-INF/NOTICE.txt");
        for (final String entry : entries) {
            assertFalse(entry.startsWith(PACKAGE_PATH) &&
                    EXPECTED_MAIN_CLASSES.contains(
                            entry.substring(PACKAGE_PATH.length())),
                    () -> testJar + " must not contain main classes: " + entry);
            assertFalse(entry.equals("META-INF/versions/9/module-info.class") ||
                    entry.equals("module-info.class"),
                    () -> testJar + " must not contain a module descriptor: " + entry);
        }
    }

    @Test
    public void testBinAssemblyContainsJarSourcesJavadocAndLegalFiles() throws IOException {
        final List<String> entries = readZipEntries(binZip);
        assertFalse(entries.isEmpty(), () -> binZip + " must not be empty");
        final String base = topLevelDirectory(binZip);
        assertTrue(entries.contains(base + mainJar.getFileName().toString()),
                () -> binZip + " must contain the main jar");
        assertTrue(entries.contains(base + sourcesJar.getFileName().toString()),
                () -> binZip + " must contain the sources jar");
        assertTrue(entries.contains(base + "apidocs/index.html"),
                () -> binZip + " must contain non-empty apidocs (regression for the " +
                        "target/site/apidocs vs target/reports/apidocs drift)");
        assertTrue(entries.stream().anyMatch(e -> e.startsWith(base + "apidocs/") &&
                e.endsWith("CSVFormat.html")),
                () -> binZip + " apidocs must include class documentation");
        for (final String legalFile : new String[] {"LICENSE.txt", "NOTICE.txt",
                "RELEASE-NOTES.txt"}) {
            assertTrue(entries.contains(base + legalFile),
                    () -> binZip + " must contain " + legalFile);
        }
        for (final String entry : entries) {
            if (entry.endsWith("/")) {
                continue;
            }
            assertFalse(entry.endsWith(".class") || entry.endsWith(".java"),
                    () -> binZip + " must not contain loose classes or sources: " +
                            entry);
        }
    }

    @Test
    public void testSrcAssemblyContainsBuildableTree() throws IOException {
        final List<String> entries = readZipEntries(srcZip);
        assertFalse(entries.isEmpty(), () -> srcZip + " must not be empty");
        final String base = topLevelDirectory(srcZip);
        assertTrue(entries.contains(base + "pom.xml"),
                () -> srcZip + " must contain pom.xml");
        assertTrue(entries.contains(base +
                "src/main/java/org/apache/commons/csv/CSVFormat.java"),
                () -> srcZip + " must contain main sources");
        assertTrue(entries.contains(base +
                "src/test/java/org/apache/commons/csv/CSVParserTest.java"),
                () -> srcZip + " must contain test sources");
        assertTrue(entries.contains(base + "LICENSE.txt"),
                () -> srcZip + " must contain LICENSE.txt");
        assertTrue(entries.contains(base + "NOTICE.txt"),
                () -> srcZip + " must contain NOTICE.txt");
        for (final String entry : entries) {
            assertFalse(entry.endsWith("Benchmark.java"),
                    () -> srcZip + " must exclude benchmark sources: " + entry);
            assertFalse(entry.contains("/target/") || entry.endsWith(".class"),
                    () -> srcZip + " must not contain build output: " + entry);
        }
    }

    private static Path locateTarget() {
        final Path dir = Paths.get("target").toAbsolutePath();
        if (Files.isDirectory(dir)) {
            return dir;
        }
        throw new IllegalStateException("target/ directory not found; run the package " +
                "phase before the release artifact checks");
    }

    private static Path findJar(final boolean testJarWanted, final String requiredEntry) {
        try (Stream<Path> jars = Files.list(targetDir)) {
            final List<Path> matches = jars
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> testJarWanted ==
                            p.getFileName().toString().endsWith("-tests.jar"))
                    .filter(p -> jarHasEntry(p, requiredEntry))
                    .collect(Collectors.toList());
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findAssemblyZip(final String suffix, final String... fragments) {
        try (Stream<Path> zips = Files.list(targetDir)) {
            final List<Path> matches = zips
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> safeZipContainsAll(p, fragments))
                    .collect(Collectors.toList());
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path requireArtifact(final Path path, final String description) {
        assertNotNull(path, () -> "Could not locate the " + description + " in " +
                targetDir + "; the artifact is either missing or empty (build it " +
                "with verify.sh)");
        return path;
    }

    private static boolean jarHasEntry(final Path jar, final String entryName) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getJarEntry(entryName) != null;
        } catch (final IOException e) {
            return false;
        }
    }

    private static boolean safeZipContainsAll(final Path zip, final String... fragments) {
        try {
            return zipContainsAll(zip, fragments);
        } catch (final IOException e) {
            return false;
        }
    }

    private static boolean zipContainsAll(final Path zip, final String... fragments)
            throws IOException {
        final Set<String> entries = new TreeSet<>(readZipEntries(zip));
        for (final String fragment : fragments) {
            if (entries.stream().noneMatch(e -> e.contains(fragment))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> readZipEntries(final Path archive) throws IOException {
        final List<String> entries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                entries.add(zipEntries.nextElement().getName());
            }
        }
        return entries;
    }

    private static String topLevelDirectory(final Path archive) throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                final int slash = name.indexOf('/');
                if (slash > 0 && name.startsWith("commons-csv-")) {
                    return name.substring(0, slash + 1);
                }
            }
        }
        throw new IllegalStateException(archive + " has no expected top-level directory");
    }

    private static Set<String> javaSourceEntries(final Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return Collections.emptySet();
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(p -> sourceRoot.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
