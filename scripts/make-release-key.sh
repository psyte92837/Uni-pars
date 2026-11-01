#!/usr/bin/env bash
# Creates a release signing key and prints the values to store as GitHub secrets.
# Keep the .jks file and passwords somewhere safe: losing them means users cannot update the app.
set -euo pipefail
out="${1:-uni-pars-release.jks}"
alias="${2:-unipars}"
read -rsp "Keystore password (min 6 chars): " pass; echo
keytool -genkeypair -v -keystore "$out" -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$pass" -keypass "$pass" -dname "CN=Uni-pars"
echo
echo "Add these repository secrets (Settings → Secrets and variables → Actions):"
echo "  SIGNING_KEYSTORE_BASE64 = (contents of ${out}.b64)"
echo "  SIGNING_STORE_PASSWORD  = <the password you entered>"
echo "  SIGNING_KEY_ALIAS       = $alias"
echo "  SIGNING_KEY_PASSWORD    = <the password you entered>"
base64 -w0 "$out" > "$out.b64" 2>/dev/null || base64 "$out" | tr -d '\n' > "$out.b64"
echo "Wrote $out.b64"
