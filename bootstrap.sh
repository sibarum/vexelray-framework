#!/usr/bin/env bash
# Builds the VexelRay stack an application needs and installs it to your local Maven repository (~/.m2), so a
# project generated from the template builds. The one script for this: every guide that used to list the order
# by hand points here instead, because five hand-written copies of the order had drifted apart.
#
#   git clone https://github.com/sibarum/vexelray-framework.git
#   ./vexelray-framework/bootstrap.sh                 # clone what is missing beside it, build, install
#   ./vexelray-framework/bootstrap.sh --from vexelray # reinstall vexelray and everything after it (after a pull)
#   ./vexelray-framework/bootstrap.sh --tests         # run each repository's tests as it goes
#   ./vexelray-framework/bootstrap.sh --check         # only say what is missing; build nothing
#
# Windows (from Git Bash) or Linux. The repositories are cloned beside this one, on their default branch.
# Each build's output goes to <this folder>/target/bootstrap/<repo>.log, and the first failure stops the run and
# names its log. The order is the dependency order of the poms: each repository needs the ones before it.
set -euo pipefail

STACK=(vexelray-build intrabuf atchung supirvast dxgi-bridge kronometer tactroller vexelray imagelib-wrapper
       vexelray-gui vexelray-framework)
ORIGIN=https://github.com/sibarum

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
logs="$here/target/bootstrap"

from= tests=0 check=0
while [ $# -gt 0 ]; do
    case "$1" in
        --from) from=${2:?--from needs a repository name}; shift 2 ;;
        --tests) tests=1; shift ;;
        --check) check=1; shift ;;
        -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (--help lists them)" >&2; exit 2 ;;
    esac
done
if [ -n "$from" ] && [[ " ${STACK[*]} " != *" $from "* ]]; then
    echo "--from $from: not one of: ${STACK[*]}" >&2; exit 2
fi

case "$(uname -s)" in
    Linux) os=linux mvn=mvn ;;
    MINGW*|MSYS*|CYGWIN*) os=windows mvn=mvn.cmd ;;
    *) echo "$(uname -s): only Windows (from Git Bash) and Linux are supported" >&2; exit 1 ;;
esac

# ---- what it needs ---------------------------------------------------------------------------------------------
missing=()
command -v git >/dev/null || missing+=("git")
command -v "$mvn" >/dev/null || missing+=("Maven ($mvn on PATH)")
command -v cargo >/dev/null || missing+=("a Rust toolchain (https://rustup.rs), for imagelib-wrapper's decoders")
if command -v java >/dev/null; then
    major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    [ "${major:-0}" -ge 25 ] || missing+=("JDK 25 (java on PATH is ${major:-unknown})")
else
    missing+=("JDK 25 (java on PATH)")
fi
if [ ${#missing[@]} -gt 0 ]; then
    echo "Install these first (docs/getting-started.md, step 1):" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi

# ---- clone what is missing ----------------------------------------------------------------------------------------
for r in "${STACK[@]}"; do
    [ -d "$root/$r/.git" ] && continue
    if [ $check = 1 ]; then echo "not cloned: $root/$r"; continue; fi
    echo "[$r] clone"
    git clone -q "$ORIGIN/$r.git" "$root/$r"
done
[ $check = 1 ] && { echo "checked: the stack goes in $root"; exit 0; }

mkdir -p "$logs"
skip=$([ $tests = 1 ] && echo "" || echo "-DskipTests")
started=$([ -z "$from" ] && echo 1 || echo 0)

for r in "${STACK[@]}"; do
    [ "$r" = "$from" ] && started=1
    [ $started = 1 ] || continue

    # imagelib-wrapper's native library is built from Rust source by its OS module and is not in the repository.
    if [ "$r" = imagelib-wrapper ]; then
        echo "[$r] native library ($os)"
        if ! "$root/$r/imagelib-wrapper-$os/native/build.sh" > "$logs/$r-native.log" 2>&1; then
            echo "[$r] native build FAILED, see $logs/$r-native.log" >&2
            [ $os = windows ] && echo "  Rust's default Windows toolchain links with the Visual Studio Build Tools." >&2
            exit 1
        fi
    fi

    echo "[$r] install"
    if ! (cd "$root/$r" && "$mvn" -B install $skip) > "$logs/$r.log" 2>&1; then
        echo "[$r] FAILED, see $logs/$r.log" >&2
        echo "  Once it is fixed: $0 --from $r" >&2
        exit 1
    fi
done

ottermate="$root/vexelray-gui/vexelray-gui-automation-cli"
out=$([ $os = linux ] && echo target-linux || echo target)
cat <<EOF

The stack is installed. Next, from $here:

  java -cp vexelray-framework-template/$out/classes dev.vexelray.framework.template.Generate --in .. --name my-app --group dev.example

and the generator prints the command that runs it. ottermate, which drives and photographs a running
application, is $ottermate/ottermate: put that folder on your PATH.
The developer guide: https://sibarum.github.io/vexelray-framework/developers/
EOF
