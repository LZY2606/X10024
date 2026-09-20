#!/bin/sh
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Single, offline-capable verification entry point shared by developers and
# CI (.github/workflows/maven.yml). It compiles and tests on every available
# JDK, then self-checks the JPMS metadata, the japicmp API baseline and the
# required/forbidden content of the published jars and distribution assemblies.
#
# Subcommands (failures always propagate via a non-zero exit status):
#   all [JDK...]     Run the matrix plus jpms, japicmp and packages (default)
#   matrix [JDK...]  Clean package (-DskipTests) then test on each JDK
#   build [JDK]      Clean package with -DskipTests on one JDK
#   test  [JDK]      Run the test suite on one JDK; fail on zero collections
#   jpms  [JDK]      Resolve the published jar as a JPMS module (JDK 9+)
#   japicmp [JDK]    Compare the build against the released API baseline
#   packages [JDK]   Build jars + bin/src assemblies and run ReleaseArtifactsIT
#   list             Print which matrix JDKs are present / missing
#
# Environment:
#   VERIFY_JDKS            Space separated JDK versions to use (default matrix)
#   JDK_HOME_<v> / JAVA_HOME_<v>
#                          Explicit home for JDK version v (e.g. JDK_HOME_17)
#   JAVA_HOME              Home used when no per-version home matches
#   VERIFY_OFFLINE=1       Add -o to every Maven invocation
#   VERIFY_ALLOW_MISSING_JDK=1
#                          Skip matrix versions that are not installed instead
#                          of failing (CI jobs pin exactly one version)
#

set -eu

# shellcheck disable=SC2209,SC1007
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

DEFAULT_MATRIX="8 11 17 21 25 26"
BASELINE_VERSION="1.14.1"

MAIN_JAR_GLOB="target/commons-csv-*.jar"

OFFLINE_ARG=""
if [ "${VERIFY_OFFLINE:-0}" = "1" ]; then
    OFFLINE_ARG="-o"
fi

# Resolve the directory this script considers the project root.
log() {
    printf '[verify] %s\n' "$*"
}

err() {
    printf '[verify] ERROR: %s\n' "$*" >&2
}

# Locate a JDK home for the requested version.
# Prints the home path on stdout; returns 1 when no matching JDK is installed.
jdk_home() {
    version=$1
    eval "explicit=\${JDK_HOME_${version}:-}"
    if [ -z "$explicit" ]; then
        eval "explicit=\${JAVA_HOME_${version}:-}"
    fi
    if [ -n "$explicit" ]; then
        if [ -x "$explicit/bin/java" ]; then
            printf '%s\n' "$explicit"
            return 0
        fi
        err "JDK_HOME_${version} points at '$explicit' but bin/java is missing"
        return 1
    fi
    # CI (actions/setup-java) and plain JAVA_HOME setups: only accept it when
    # the home really runs the requested major version. This can never turn a
    # missing JDK into a silent run on the current JDK.
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        if [ "$(jdk_version_of "$JAVA_HOME")" = "$version" ]; then
            printf '%s\n' "$JAVA_HOME"
            return 0
        fi
    fi
    # macOS Homebrew
    candidate="/opt/homebrew/opt/openjdk@${version}/libexec/openjdk.jdk/Contents/Home"
    if [ -x "$candidate/bin/java" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi
    candidate="/opt/homebrew/opt/openjdk${version}/libexec/openjdk.jdk/Contents/Home"
    if [ -x "$candidate/bin/java" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi
    # macOS /usr/libexec/java_home
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        candidate=$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    # Linux SDKMAN, common CI locations and Jabba/asdf
    for candidate in \
        "$HOME/.sdkman/candidates/java/${version}*/" \
        "/usr/lib/jvm/java-${version}-"* \
        "/opt/java/${version}" \
        "$HOME/.jabba/jdk/${version}"*/ \
        "$HOME/.asdf/installs/java/${version}"*/; do
        # shellcheck disable=SC2086
        if [ -x "${candidate}bin/java" ]; then
            printf '%s\n' "${candidate%/}"
            return 0
        fi
    done
    return 1
}

# Print the major Java version reported by a JDK home.
jdk_version_of() {
    home=$1
    raw=$("$home/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
    first=$(printf '%s' "$raw" | cut -d. -f1)
    if [ "$first" = "1" ]; then
        first=$(printf '%s' "$raw" | cut -d. -f2)
    fi
    # Strip EA/GA suffixes such as "27-ea"; only the leading major digits matter.
    printf '%s' "$first" | sed 's/[^0-9].*$//'
}

# Fail the run when the Maven version in use is not the requested one.
# A missing JDK must never silently degrade to whatever JDK runs Maven.
assert_maven_jdk() {
    expected=$1
    home=$2
    actual=$(jdk_version_of "$home")
    if [ "$actual" != "$expected" ]; then
        err "Requested JDK ${expected} but '${home}/bin/java' reports JDK ${actual}"
        err "Refusing to continue: results for JDK ${expected} would be silently wrong."
        return 1
    fi
    mvn_version=$(JAVA_HOME="$home" mvn -version 2>/dev/null | awk '/Maven home/{next} /Apache Maven/ {print $3}')
    log "Maven ${mvn_version:-unknown} on JDK ${actual} (${home})"
}

run_maven() {
    home=$1
    shift
    log "mvn $*"
    JAVA_HOME="$home" mvn $OFFLINE_ARG "$@"
}

# Reject stale main jars: an artifact older than a source or pom file cannot
# have been produced from the current checkout by this invocation.
assert_no_stale_artifacts() {
    stale=""
    newest_source=$(find pom.xml src/main src/test -type f -exec ls -t {} + 2>/dev/null \
        | head -1)
    if [ -z "$newest_source" ]; then
        return 0
    fi
    for jar in $MAIN_JAR_GLOB; do
        [ -f "$jar" ] || continue
        if [ "$newest_source" -nt "$jar" ]; then
            stale="$stale $jar"
        fi
    done
    if [ -n "$stale" ]; then
        err "Stale artifact(s) older than the current sources/pom:$stale"
        err "Run 'verify.sh build' first; refusing to test output from an earlier"
        err "checkout or a different JDK."
        return 1
    fi
}

# Count Surefire reports and fail on an empty collection or any failure.
# Surefire writes one *.txt per executed test class.
check_surefire_reports() {
    report_dir="${SUREFIRE_REPORT_DIR:-target/surefire-reports}"
    freshness_marker="${SUREFIRE_FRESHNESS_MARKER:-target/.verify-test-started}"
    if [ ! -d "$report_dir" ]; then
        err "No Surefire report directory ($report_dir): the test phase did not run."
        return 1
    fi
    # Reports older than this invocation's marker are leftovers from a
    # previous run, for example one where no test pattern matched.
    if [ -f "$freshness_marker" ]; then
        for report in "$report_dir"/*.txt; do
            [ -e "$report" ] || continue
            if [ "$freshness_marker" -nt "$report" ]; then
                err "Stale Surefire report $report predates this test invocation;"
                err "the current run produced no results (zero collections)."
                return 1
            fi
        done
    fi
    set -- "$report_dir"/*.txt
    if [ ! -e "$1" ]; then
        err "Zero Surefire test collections in $report_dir; refusing an empty test run."
        return 1
    fi
    collection_count=0
    total_tests=0
    while IFS= read -r report; do
        collection_count=$((collection_count + 1))
        # Header line: "Tests run: 12, Failures: 0, Errors: 0, Skipped: 0..."
        line=$(head -4 "$report" | grep 'Tests run:' | head -1 || true)
        if [ -z "$line" ]; then
            err "Report $report has no 'Tests run:' line; cannot prove tests executed."
            return 1
        fi
        n=$(printf '%s\n' "$line" | sed -n 's/.*Tests run: \([0-9][0-9]*\).*/\1/p')
        if [ -z "$n" ]; then
            err "Could not parse test count from $report: $line"
            return 1
        fi
        if [ "$n" -eq 0 ]; then
            err "Empty test collection in $report; refusing a zero-test run."
            return 1
        fi
        total_tests=$((total_tests + n))
        failures=$(printf '%s\n' "$line" | sed -n 's/.*Failures: \([0-9][0-9]*\).*/\1/p')
        errors=$(printf '%s\n' "$line" | sed -n 's/.*Errors: \([0-9][0-9]*\).*/\1/p')
        if [ "${failures:-1}" != "0" ] || [ "${errors:-1}" != "0" ]; then
            err "Failures or errors reported by $report: $line"
            return 1
        fi
    done <<EOF_REPORTS
$(ls "$report_dir"/*.txt 2>/dev/null)
EOF_REPORTS
    if [ "$collection_count" -eq 0 ] || [ "$total_tests" -eq 0 ]; then
        err "Zero test collections or zero tests executed; refusing an empty test run."
        return 1
    fi
    log "Surefire: ${total_tests} tests in ${collection_count} collections, all passed."
}

cmd_build() {
    version=${1:?usage: build JDK_VERSION}
    home=$(jdk_home "$version") || { err "JDK ${version} is not installed"; exit 2; }
    assert_maven_jdk "$version" "$home"
    run_maven "$home" clean package -DskipTests
    jar_count=0
    for jar in $MAIN_JAR_GLOB; do
        [ -f "$jar" ] || continue
        jar_count=$((jar_count + 1))
        if [ ! -s "$jar" ]; then
            err "Built jar $jar is empty"; exit 1
        fi
    done
    if [ "$jar_count" -eq 0 ]; then
        err "No main jar produced by package; refusing an empty build."
        exit 1
    fi
    log "package OK on JDK ${version}"
}

cmd_test() {
    version=${1:?usage: test JDK_VERSION}
    home=$(jdk_home "$version") || { err "JDK ${version} is not installed"; exit 2; }
    assert_maven_jdk "$version" "$home"
    assert_no_stale_artifacts
    # Touch a marker before the run so reports left over from an earlier
    # invocation cannot masquerade as current results.
    mkdir -p target
    : > target/.verify-test-started
    run_maven "$home" test
    check_surefire_reports
    log "test OK on JDK ${version}"
}

cmd_matrix() {
    versions=$*
    if [ -z "$versions" ]; then
        versions=${VERIFY_JDKS:-$DEFAULT_MATRIX}
    fi
    missing=""
    tested=""
    for version in $versions; do
        if home=$(jdk_home "$version"); then
            # Fresh artifacts per JDK so stale output can never be reused.
            cmd_build "$version"
            cmd_test "$version"
            tested="$tested $version"
        else
            missing="$missing $version"
            log "NOTE: optional JDK ${version} is not installed on this machine;"
            log "      it was NOT substituted by the current JDK and will be covered by CI."
        fi
    done
    if [ -n "$missing" ] && [ "${VERIFY_ALLOW_MISSING_JDK:-0}" != "1" ]; then
        err "Required JDK version(s) missing:$missing"
        err "Set VERIFY_ALLOW_MISSING_JDK=1 to skip them explicitly, or install them."
        err "Installed matrix versions tested:${tested:- none}"
        exit 3
    fi
    log "matrix OK on JDKs:${tested:-none}; missing (skipped):${missing:-none}"
}

# Locate the runtime dependency jars needed on the JPMS module path.
module_path() {
    m2=${HOME}/.m2/repository
    io_version=$(sed -n 's:.*<commons.io.version>\(.*\)</commons.io.version>.*:\1:p' pom.xml | head -1)
    codec_version=$(sed -n 's:.*<commons.codec.version>\(.*\)</commons.codec.version>.*:\1:p' pom.xml | head -1)
    io_jar="$m2/commons-io/commons-io/${io_version}/commons-io-${io_version}.jar"
    codec_jar="$m2/commons-codec/commons-codec/${codec_version}/commons-codec-${codec_version}.jar"
    if [ ! -f "$io_jar" ] || [ ! -f "$codec_jar" ]; then
        err "Module path dependency missing: $io_jar $codec_jar"
        err "Run the build once with network access to populate the local repository."
        return 1
    fi
    printf '%s:%s\n' "$io_jar" "$codec_jar"
}

cmd_jpms() {
    version=${1:-${VERIFY_PRIMARY_JDK:-21}}
    home=$(jdk_home "$version") || { err "JDK ${version} is not installed"; exit 2; }
    assert_maven_jdk "$version" "$home"

    jar=""
    for candidate in $MAIN_JAR_GLOB; do
        case "$candidate" in
            *-javadoc.jar|*-test-sources.jar|*-sources.jar|*-tests.jar) ;;
            *) [ -f "$candidate" ] && jar=$candidate ;;
        esac
    done
    if [ -z "$jar" ]; then
        err "Main jar missing for the JPMS check; run 'verify.sh build ${version}' first."
        exit 1
    fi

    major=$(jdk_version_of "$home")
    if [ "$major" -lt 9 ]; then
        log "JDK ${major}: real modules are unavailable; checking Automatic-Module-Name."
        manifest_tmp=$(mktemp -d)
        # shellcheck disable=SC2209,SC1007
        jar_abs=$(CDPATH= cd -- "$(dirname -- "$jar")" && pwd)/$(basename -- "$jar")
        (cd "$manifest_tmp" && "$home/bin/jar" xf "$jar_abs" META-INF/MANIFEST.MF)
        if ! grep -q '^Automatic-Module-Name: org.apache.commons.csv' \
                "$manifest_tmp/META-INF/MANIFEST.MF"; then
            err "Main jar does not declare Automatic-Module-Name: org.apache.commons.csv"
            rm -rf "$manifest_tmp"
            exit 1
        fi
        rm -rf "$manifest_tmp"
        log "jpms OK on JDK ${major} (automatic module name only)"
        return 0
    fi

    mpath=$(module_path)
    # 1. The published jar must resolve as an explicit named module.
    description=$("$home/bin/java" --module-path "${jar}:${mpath}" \
        --describe-module org.apache.commons.csv 2>&1) || {
        err "java --describe-module failed on ${jar}:"
        printf '%s\n' "$description" >&2
        exit 1
    }
    printf '%s\n' "$description"
    printf '%s\n' "$description" | grep -q '^org.apache.commons.csv@' || {
        err "Module is not named org.apache.commons.csv:"
        printf '%s\n' "$description" >&2
        exit 1
    }
    printf '%s\n' "$description" | grep -q 'exports org.apache.commons.csv' || {
        err "Module does not export org.apache.commons.csv"
        exit 1
    }

    # 2. Smoke test: compile and launch a tiny module that requires the jar.
    work=$(mktemp -d)
    mkdir -p "$work/src/consumer/consumer" "$work/out"
    cat > "$work/src/consumer/module-info.java" <<'JAVA'
module consumer {
    requires org.apache.commons.csv;
    exports consumer;
}
JAVA
    cat > "$work/src/consumer/consumer/ConsumerCheck.java" <<'JAVA'
package consumer;

import org.apache.commons.csv.CSVFormat;

public class ConsumerCheck {
    public static void main(final String[] args) {
        if (!CSVFormat.DEFAULT.equals(CSVFormat.DEFAULT)) {
            throw new IllegalStateException("identity check failed");
        }
        System.out.println("module smoke ok");
    }
}
JAVA
    if ! "$home/bin/javac" --module-path "${jar}:${mpath}" \
            -d "$work/out" \
            "$work/src/consumer/module-info.java" \
            "$work/src/consumer/consumer/ConsumerCheck.java"; then
        err "Consumer module failed to compile against the published jar"
        rm -rf "$work"
        exit 1
    fi
    if ! output=$("$home/bin/java" --module-path "${jar}:${mpath}:$work/out" \
            --module consumer/consumer.ConsumerCheck 2>&1); then
        err "Consumer module failed to run:"
        printf '%s\n' "$output" >&2
        rm -rf "$work"
        exit 1
    fi
    printf '%s\n' "$output" | grep -q 'module smoke ok' || {
        err "Unexpected module smoke output: $output"
        rm -rf "$work"
        exit 1
    }
    rm -rf "$work"
    log "jpms OK on JDK ${major}"
}

cmd_japicmp() {
    version=${1:-${VERIFY_PRIMARY_JDK:-21}}
    home=$(jdk_home "$version") || { err "JDK ${version} is not installed"; exit 2; }
    assert_maven_jdk "$version" "$home"
    run_maven "$home" -Pjapicmp verify -DskipTests
    if [ ! -s target/japicmp/japicmp.diff ] && [ ! -s target/japicmp/japicmp.xml ]; then
        err "japicmp produced no report against baseline ${BASELINE_VERSION}."
        exit 1
    fi
    log "japicmp OK against ${BASELINE_VERSION} on JDK ${version}"
}

cmd_packages() {
    version=${1:-${VERIFY_PRIMARY_JDK:-21}}
    home=$(jdk_home "$version") || { err "JDK ${version} is not installed"; exit 2; }
    assert_maven_jdk "$version" "$home"
    run_maven "$home" clean verify -Prelease-verify
    # Verify must leave the Failsafe content checks and both assemblies behind.
    if [ ! -s target/failsafe-reports/org.apache.commons.csv.ReleaseArtifactsIT.txt ]; then
        err "ReleaseArtifactsIT did not run; artifact content is unverified."
        exit 1
    fi
    for assembly in target/commons-csv-*-bin.tar.gz target/commons-csv-*-bin.zip \
            target/commons-csv-*-src.tar.gz target/commons-csv-*-src.zip; do
        [ -s "$assembly" ] || { err "Distribution assembly missing or empty: $assembly"; exit 1; }
    done
    log "packages OK on JDK ${version}"
}

cmd_all() {
    versions=$*
    # shellcheck disable=SC2086 # version list is intentionally word-split
    cmd_matrix $versions
    primary=${VERIFY_PRIMARY_JDK:-21}
    if ! home=$(jdk_home "$primary"); then
        home=""
        for candidate in ${versions:-${VERIFY_JDKS:-$DEFAULT_MATRIX}}; do
            if jdk_home "$candidate" >/dev/null 2>&1; then
                primary=$candidate
                home=$(jdk_home "$candidate")
                break
            fi
        done
    fi
    if [ -z "$home" ]; then
        err "No usable JDK for jpms/japicmp/packages checks."
        exit 2
    fi
    log "Using JDK ${primary} as primary for jpms/japicmp/packages."
    cmd_build "$primary"
    cmd_jpms "$primary"
    cmd_japicmp "$primary"
    cmd_packages "$primary"
    log "all checks passed"
}

cmd_list() {
    versions=${VERIFY_JDKS:-$DEFAULT_MATRIX}
    present=""
    missing=""
    for version in $versions; do
        if home=$(jdk_home "$version"); then
            log "JDK ${version}: present at ${home}"
            present="$present $version"
        else
            log "JDK ${version}: NOT installed (will be reported, not substituted)"
            missing="$missing $version"
        fi
    done
    log "present:${present:-none}; missing:${missing:-none}"
}

command=${1:-all}
if [ $# -gt 0 ]; then
    shift
fi
case "$command" in
    all) cmd_all "$@" ;;
    matrix) cmd_matrix "$@" ;;
    build) cmd_build "$1" ;;
    test) cmd_test "$1" ;;
    jpms) cmd_jpms "$1" ;;
    japicmp) cmd_japicmp "$1" ;;
    packages) cmd_packages "$1" ;;
    list) cmd_list ;;
    *)
        err "Unknown subcommand: $command"
        err "Usage: $0 {all|matrix|build|test|jpms|japicmp|packages|list} [JDK...]"
        exit 64
        ;;
esac
