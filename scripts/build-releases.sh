#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="0.1.0"
release_dir="$root_dir/releases/$release_version"
gradle_bin="${GRADLE_BIN:-gradle}"
gradle_user_home_path="${BRIEFEST_BOXER_GRADLE_USER_HOME:-}"
java21_home_path="${BRIEFEST_BOXER_JAVA21_HOME:-}"
java17_home_path="${BRIEFEST_BOXER_JAVA17_HOME:-}"
java8_home_path="${BRIEFEST_BOXER_JAVA8_HOME:-}"
java25_home_path="${BRIEFEST_BOXER_JAVA25_HOME:-${JAVA_HOME:-}}"
gradle_args=(--no-daemon)
if [[ -n "$gradle_user_home_path" ]]; then
    gradle_args+=(--gradle-user-home "$gradle_user_home_path")
fi
stage_dir="$(mktemp -d "$root_dir/releases/.${release_version}.XXXXXX")"
trap 'rm -rf "$stage_dir"' EXIT HUP INT TERM

for project_dir in "$root_dir"/versions/*/fabric "$root_dir"/versions/*/forge; do
    [[ -d "$project_dir" ]] || continue
    printf '\nBuilding %s\n' "${project_dir#"$root_dir"/}"
    project_gradle_bin="$gradle_bin"
    if [[ -x "$project_dir/gradlew" ]]; then
        project_gradle_bin="$project_dir/gradlew"
    fi
    project_java_home="$java21_home_path"
    project_gradle_args=("${gradle_args[@]}")
    if [[ "$project_dir" == */26.2/* ]]; then
        project_java_home="$java25_home_path"
    elif [[ "$project_dir" == */1.8.9/forge || "$project_dir" == */1.16.5/forge ]]; then
        project_java_home="$java8_home_path"
        project_gradle_args+=("-Dorg.gradle.jvmargs=-Xmx4G -XX:-UseGCOverheadLimit")
    elif [[ "$project_dir" == */1.20.1/forge ]]; then
        project_java_home="$java17_home_path"
    fi
    if [[ -n "$project_java_home" ]]; then
        BRIEFEST_BOXER_JAVA8_HOME="$java8_home_path" BRIEFEST_BOXER_JAVA17_HOME="$java17_home_path" JAVA_HOME="$project_java_home" "$project_gradle_bin" "${project_gradle_args[@]}" -p "$project_dir" clean build --console=plain
    else
        BRIEFEST_BOXER_JAVA8_HOME="$java8_home_path" BRIEFEST_BOXER_JAVA17_HOME="$java17_home_path" "$project_gradle_bin" "${project_gradle_args[@]}" -p "$project_dir" clean build --console=plain
    fi

    minecraft_version="${project_dir#"$root_dir"/versions/}"
    minecraft_version="${minecraft_version%%/*}"
    loader="${project_dir##*/}"
    jar_file="$(find "$project_dir/build/libs" -maxdepth 1 -type f -name "briefest-boxer-${minecraft_version}-${loader}-*.jar" ! -name '*-sources.jar' ! -name '*-dev.jar' -print -quit)"
    if [[ -z "$jar_file" ]]; then
        printf 'No distributable jar found for %s\n' "$project_dir" >&2
        exit 1
    fi
    cp "$jar_file" "$stage_dir/"
done

if ! compgen -G "$stage_dir/*.jar" >/dev/null; then
    printf 'No release jars were built\n' >&2
    exit 1
fi

mkdir -p "$release_dir"
rm -f "$release_dir"/*.jar "$release_dir/SHA256SUMS"
cp "$stage_dir"/*.jar "$release_dir/"
(cd "$release_dir" && shasum -a 256 ./*.jar > SHA256SUMS)
printf '\nRelease jars and checksums written to %s\n' "$release_dir"
