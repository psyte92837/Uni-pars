#!/usr/bin/env bash
# Runs JVM module tasks (engine, ai) without the Android SDK/AGP, e.g. in cloud sessions where
# dl.google.com is unreachable. Default: tests of both modules. Retries Maven Central 429s.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dir="${TMPDIR:-/tmp}/unipars-jvmcheck"
mkdir -p "$dir"
cat > "$dir/settings.gradle.kts" <<KTS
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("$root/gradle/libs.versions.toml")) } }
}
rootProject.name = "jvmcheck"
include(":engine", ":ai")
project(":engine").projectDir = file("$root/engine")
project(":ai").projectDir = file("$root/ai")
KTS
cat > "$dir/build.gradle.kts" <<'KTS'
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
KTS
tasks=("$@"); [ ${#tasks[@]} -eq 0 ] && tasks=(:engine:test :ai:test)
gradle_cmd="$(command -v gradle || echo "$root/gradlew")"
for attempt in 1 2 3 4; do
  if "$gradle_cmd" -p "$dir" --console=plain -q "${tasks[@]}" > "$dir/out.txt" 2>&1; then status=0; break; fi
  status=1
  grep -q "429" "$dir/out.txt" || break
  sleep $((attempt * 20))
done
grep -v "Picked up JAVA_TOOL_OPTIONS" "$dir/out.txt" | grep -E "^e:|FAILED|failed|error:" | head -40
for f in "$root"/{engine,ai}/build/test-results/test/*.xml; do
  [ -f "$f" ] && grep -h "<testsuite" "$f" | sed -E 's/.*name="([^"]+)" tests="([0-9]+)" skipped="[0-9]+" failures="([0-9]+)" errors="([0-9]+)".*/\1 tests=\2 failures=\3 errors=\4/'
done
exit $status
