#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Converged verification entry point for Apache Commons CSV, used by both
# local development and CI (.github/workflows/maven.yml).
#
# For every available JDK in the matrix this script:
#   1. compiles and packages the project (mvn clean package -DskipTests),
#   2. runs the full test suite (mvn test), which includes the self-checks
#      for the module descriptor, Automatic-Module-Name, the public API
#      baseline and the required/forbidden contents of the three
#      distribution packages (main jar, sources jar, tests jar),
#   3. rejects empty, missing or stale build artifacts,
#   4. rejects silently skipped or empty test sets.
#
# Usage:
#   ./verify.sh                 verify on every available JDK of the default matrix
#   ./verify.sh --jdk 17        require JDK 17; fail if it cannot be located
#   ./verify.sh --jdk 8 --jdk 17
#
# Environment:
#   VERIFY_JDKS       space-separated default matrix (default: "8 11 17 21 25 26")
#   VERIFY_MVN_OPTS   Maven flags (default: "--batch-mode --no-transfer-progress")
#   VERIFY_STATIC     run checkstyle/spotbugs/pmd once on the first JDK (default: 1)
#   MVN               Maven executable (default: mvn)
#
# A JDK requested with --jdk that cannot be located is a hard error; matrix
# JDKs that are not installed are reported as SKIP with the reason. The
# current JDK is never silently substituted for a requested one.

set -euo pipefail

readonly PROG="${0##*/}"
readonly DEFAULT_MATRIX="${VERIFY_JDKS:-8 11 17 21 25 26}"
readonly MVN="${MVN:-mvn}"
readonly MVN_OPTS="${VERIFY_MVN_OPTS:---batch-mode --no-transfer-progress}"
readonly VERIFY_STATIC="${VERIFY_STATIC:-1}"

# Test sets whose surefire reports must exist and contain at least one
# executed test; a missing or empty report means a verification set was
# silently skipped.
readonly REQUIRED_TEST_SETS="
org.apache.commons.csv.verify.ReleaseArtifactsTest
org.apache.commons.csv.verify.PublicApiBaselineTest
"

REQUIRED_JDKS=()

log() {
    printf '%s\n' "$*"
}

die() {
    log "ERROR: $*" >&2
    exit 1
}

usage() {
    sed -n '2,40p' "$0" | sed 's/^#//'
}

# Prints the major version of the JDK at $1 (e.g. 8, 11, 17, 27-ea).
java_major() {
    local version
    version=$("$1/bin/java" -version 2>&1 | head -n 1 | sed -E 's/^.*version "([^"]+)".*$/\1/')
    if [[ "${version}" == 1.* ]]; then
        version="${version#1.}"
    fi
    printf '%s' "${version%%.*}"
}

# Resolves a JDK home for the requested version $1.
# Prints the home directory and returns 0, or returns 1 if not found.
resolve_jdk_home() {
    local want="$1" home=""
    local var="JAVA_HOME_${want//[^A-Za-z0-9]/_}"
    home="${!var:-}"
    if [[ -n "${home}" ]]; then
        if [[ -x "${home}/bin/java" ]]; then
            printf '%s' "${home}"
            return 0
        fi
        return 1
    fi
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] && [[ "$(java_major "${JAVA_HOME}")" == "${want}" ]]; then
        printf '%s' "${JAVA_HOME}"
        return 0
    fi
    if [[ -f "${HOME}/.m2/toolchains.xml" ]]; then
        home=$(awk -v want="${want}" '
            /<version>/ { gsub(/.*<version>[ \t]*|[ \t]*<\/version>.*/, ""); v = $0 }
            /<jdkHome>/ { gsub(/.*<jdkHome>[ \t]*|[ \t]*<\/jdkHome>.*/, ""); if (v == want) { print; exit } }
        ' "${HOME}/.m2/toolchains.xml")
        if [[ -n "${home}" && -x "${home}/bin/java" ]]; then
            printf '%s' "${home}"
            return 0
        fi
    fi
    if [[ -x /usr/libexec/java_home ]]; then
        home=$(/usr/libexec/java_home -v "${want}" 2>/dev/null) || home=""
        if [[ -n "${home}" && -x "${home}/bin/java" ]]; then
            printf '%s' "${home}"
            return 0
        fi
    fi
    if command -v brew >/dev/null 2>&1; then
        local prefix
        prefix=$(brew --prefix "openjdk@${want}" 2>/dev/null) || prefix=""
        if [[ -n "${prefix}" && -x "${prefix}/libexec/openjdk.jdk/Contents/Home/bin/java" ]]; then
            printf '%s' "${prefix}/libexec/openjdk.jdk/Contents/Home"
            return 0
        fi
    fi
    return 1
}

# Runs a Maven command for the given JDK home, propagating its exit status.
run_mvn() {
    local jdk_home="$1"
    shift
    log "  -> JAVA_HOME=${jdk_home} ${MVN} ${MVN_OPTS} $*"
    local status=0
    JAVA_HOME="${jdk_home}" PATH="${jdk_home}/bin:${PATH}" ${MVN} ${MVN_OPTS} "$@" || status=$?
    if [[ ${status} -ne 0 ]]; then
        log "ERROR: Maven goal(s) '$*' failed with exit code ${status} for JDK ${jdk_home}" >&2
        exit "${status}"
    fi
}

# Rejects missing, empty or stale distribution artifacts in target/.
# $1 = marker file created immediately before the build started.
check_artifacts_fresh() {
    local marker="$1" jdk_label="$2"
    local jar
    for jar in target/*.jar; do
        [[ -e "${jar}" ]] || die "no jars found in target/ after build on ${jdk_label}"
        [[ -s "${jar}" ]] || die "artifact ${jar} is empty after build on ${jdk_label}"
        [[ "${jar}" -nt "${marker}" ]] || die "artifact ${jar} is stale (older than the build start) on ${jdk_label}; run 'mvn clean'"
    done
}

# Rejects missing, empty or failed test sets in target/surefire-reports/.
check_test_sets() {
    local jdk_label="$1"
    local reports=target/surefire-reports
    [[ -d "${reports}" ]] || die "no surefire reports directory after 'mvn test' on ${jdk_label}"
    local total
    total=$(grep -h '^Tests run' "${reports}"/*.txt 2>/dev/null | awk -F'[:,]' '{s += $2} END {print s + 0}')
    [[ "${total}" -gt 0 ]] || die "zero tests were collected on ${jdk_label}; refusing to accept an empty test run"
    local test_set report count
    for test_set in ${REQUIRED_TEST_SETS}; do
        report="${reports}/${test_set}.txt"
        [[ -f "${report}" ]] || die "required test set ${test_set} did not run on ${jdk_label} (no ${report})"
        count=$(sed -n 's/^Tests run: \([0-9]*\),.*/\1/p' "${report}" | head -n 1)
        [[ -n "${count}" && "${count}" -gt 0 ]] || die "required test set ${test_set} collected zero tests on ${jdk_label}"
        if grep -qE 'Failures: [1-9]|Errors: [1-9]' "${report}"; then
            die "required test set ${test_set} has failures on ${jdk_label}: $(grep '^Tests run' "${report}")"
        fi
    done
    log "  -> ${total} tests executed, required verification sets present"
}

verify_jdk() {
    local jdk_home="$1"
    local version
    version=$("${jdk_home}/bin/java" -version 2>&1 | head -n 1)
    log "==> Verifying with JDK ${jdk_home} (${version})"
    local marker
    marker=$(mktemp -t verify-commons-csv)
    run_mvn "${jdk_home}" clean package -DskipTests
    run_mvn "${jdk_home}" test
    check_artifacts_fresh "${marker}" "${jdk_home}"
    check_test_sets "${jdk_home}"
    rm -f "${marker}"
}

run_static_checks() {
    local jdk_home="$1"
    log "==> Running lifecycle gates and static analysis once with JDK ${jdk_home}"
    # Reuses the coverage data of the preceding 'mvn test' run; keeps the
    # jacoco and changes-validate gates that the default lifecycle binds to
    # the verify phase.
    run_mvn "${jdk_home}" verify -DskipTests
    run_mvn "${jdk_home}" checkstyle:check spotbugs:check pmd:check pmd:cpd-check
}

main() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
        --jdk)
            [[ $# -ge 2 ]] || die "--jdk requires a version argument"
            REQUIRED_JDKS+=("$2")
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1 (see --help)"
            ;;
        esac
    done

    local candidates=()
    local strict=0
    if [[ ${#REQUIRED_JDKS[@]} -gt 0 ]]; then
        candidates=("${REQUIRED_JDKS[@]}")
        strict=1
    else
        read -r -a candidates <<<"${DEFAULT_MATRIX}"
    fi

    local verified=()
    local want home
    for want in "${candidates[@]}"; do
        home=$(resolve_jdk_home "${want}") || home=""
        if [[ -z "${home}" ]]; then
            if [[ ${strict} -eq 1 ]]; then
                die "requested JDK ${want} could not be located (set JAVA_HOME_${want}, ~/.m2/toolchains.xml, or install it); refusing to substitute another JDK"
            fi
            log "SKIP: JDK ${want} is not available on this machine; not substituting the current JDK"
            continue
        fi
        verify_jdk "${home}"
        if [[ ${VERIFY_STATIC} -eq 1 && ${#verified[@]} -eq 0 ]]; then
            run_static_checks "${home}"
        fi
        verified+=("${want}@${home}")
    done

    if [[ ${#verified[@]} -eq 0 ]]; then
        die "no JDK from the matrix could be used; nothing was verified (zero collection is a failure)"
    fi
    log "==> OK: verified on ${#verified[@]} JDK(s): ${verified[*]}"
}

main "$@"
