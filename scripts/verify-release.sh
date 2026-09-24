#!/usr/bin/env bash
#
# Verifies that a clean external Maven project can consume staged sqlcj
# artifacts.
#
# The script stages the reactor into an isolated local Maven repository, runs
# the packaged CLI from that repository, and then copies examples/maven-postgresql
# outside the repository to generate, compile, and execute it against a running
# PostgreSQL 16 server. The sqlcj source tree, its reactor classes, and its
# target directories are never placed on the sample's classpath.
#
# Requirements: JDK 21, Maven, psql, and a reachable PostgreSQL server.
#
# Connection settings, defaulting to the values used by docs/quickstart.md:
#
#   SQLCJ_SAMPLE_JDBC_URL      jdbc:postgresql://localhost:5432/quickstart
#   SQLCJ_SAMPLE_DB_USER       quickstart
#   SQLCJ_SAMPLE_DB_PASSWORD   quickstart

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/.." && pwd)

jdbc_url=${SQLCJ_SAMPLE_JDBC_URL:-jdbc:postgresql://localhost:5432/quickstart}
db_user=${SQLCJ_SAMPLE_DB_USER:-quickstart}
db_password=${SQLCJ_SAMPLE_DB_PASSWORD:-quickstart}

workspace=$(mktemp -d "${TMPDIR:-/tmp}/sqlcj-verify-release.XXXXXXXXXX")
local_repo="${workspace}/repository"
sample_dir="${workspace}/sample"

trap 'rm -rf "${workspace}"' EXIT

log() {
    printf '\n=== %s\n' "$1"
}

fail() {
    printf 'verify-release: %s\n' "$1" >&2
    exit 1
}

sample_mvn() {
    mvn --batch-mode --no-transfer-progress \
        -f "${sample_dir}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" \
        -Dsqlcj.version="${version}" \
        "$@"
}

command -v psql >/dev/null 2>&1 || fail "psql is required to apply the sample schema"

log "Staging the reactor into ${local_repo}"

mvn --batch-mode --no-transfer-progress \
    -f "${repo_root}/pom.xml" \
    -Dmaven.repo.local="${local_repo}" \
    -Dmaven.test.skip=true \
    install

version=$(
    mvn --batch-mode --no-transfer-progress --quiet \
        -f "${repo_root}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" \
        -Dexpression=project.version \
        -DforceStdout \
        help:evaluate | tail -n 1 | tr -d '[:space:]'
)

[ -n "${version}" ] || fail "could not determine the reactor version"

log "Verifying the staged artifacts for version ${version}"

cli_jar="${local_repo}/dev/sqlcj/sqlcj-cli/${version}/sqlcj-cli-${version}.jar"
runtime_jar="${local_repo}/dev/sqlcj/sqlcj-runtime/${version}/sqlcj-runtime-${version}.jar"

[ -f "${cli_jar}" ] || fail "the staged CLI is missing: ${cli_jar}"
[ -f "${runtime_jar}" ] || fail "the staged runtime is missing: ${runtime_jar}"

reported_version=$(java -jar "${cli_jar}" version | tr -d '[:space:]')

[ "${reported_version}" = "${version}" ] \
    || fail "the packaged CLI reports '${reported_version}' instead of '${version}'"

log "Verifying the handled configuration failure"

failure_dir="${workspace}/handled-failure"
mkdir -p "${failure_dir}"

set +e
failure_output=$(cd "${failure_dir}" && java -jar "${cli_jar}" generate 2>&1)
failure_status=$?
set -e

[ "${failure_status}" -eq 1 ] \
    || fail "a missing configuration file exited with ${failure_status} instead of 1"

printf '%s\n' "${failure_output}" | grep -q '^sqlcj: ' \
    || fail "the handled failure did not print an 'sqlcj: ' diagnostic: ${failure_output}"

if printf '%s\n' "${failure_output}" | grep -qE 'Exception|^[[:space:]]+at .*\('; then
    fail "the handled failure printed a stack trace: ${failure_output}"
fi

log "Copying the external sample to ${sample_dir}"

cp -R "${repo_root}/examples/maven-postgresql" "${sample_dir}"
rm -rf "${sample_dir}/target"

log "Generating sources with the packaged CLI"

(cd "${sample_dir}" && java -jar "${cli_jar}" generate)

generated_root="${sample_dir}/target/generated-sources/sqlcj"
generated_package="${generated_root}/com/example/app/db"

[ -f "${generated_package}/AuthorRepository.java" ] \
    || fail "the expected generated source is missing: ${generated_package}/AuthorRepository.java"

generated_count=$(find "${generated_root}" -type f -name '*.java' | wc -l)

[ "${generated_count}" -eq 1 ] \
    || fail "expected 1 generated source but found ${generated_count}"

for method in createAuthor getAuthor listAuthors updateAuthorBio deleteAuthor; do
    grep -q " ${method}(" "${generated_package}/AuthorRepository.java" \
        || fail "the generated repository is missing the ${method} method"
done

find "${generated_root}" -type f -name '*.java' | sort

log "Verifying the sample dependency classpath"

classpath_file="${workspace}/sample-classpath.txt"

sample_mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    "-Dmdep.outputFile=${classpath_file}"

grep -q "sqlcj-runtime-${version}.jar" "${classpath_file}" \
    || fail "the sample classpath does not use the staged runtime artifact"

if grep -q "${repo_root}" "${classpath_file}"; then
    fail "the sample classpath references the sqlcj source tree: $(cat "${classpath_file}")"
fi

tr ':' '\n' < "${classpath_file}"

log "Applying the sample schema to ${jdbc_url}"

# psql accepts the JDBC URL as a connection URI once the 'jdbc:' prefix is
# removed, so the sample and the schema reset share one setting.
PGPASSWORD="${db_password}" psql \
    --set=ON_ERROR_STOP=1 \
    --quiet \
    --username "${db_user}" \
    --command 'DROP TABLE IF EXISTS authors;' \
    --file "${sample_dir}/sql/schema.sql" \
    "${jdbc_url#jdbc:}"

log "Compiling the sample against the staged runtime"

sample_mvn compile

log "Running the sample against PostgreSQL"

sample_output="${workspace}/sample-output.txt"

export SQLCJ_SAMPLE_JDBC_URL="${jdbc_url}"
export SQLCJ_SAMPLE_DB_USER="${db_user}"
export SQLCJ_SAMPLE_DB_PASSWORD="${db_password}"

sample_mvn exec:java | tee "${sample_output}"

grep -q 'sqlcj sample verification passed' "${sample_output}" \
    || fail "the sample did not report a successful run"

log "Release verification passed for version ${version}"
