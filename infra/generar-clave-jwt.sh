#!/bin/sh
# Genera la clave RSA de 2048 bits con la que Identidad firma los JWT y la
# imprime como PKCS#8 DER en Base64 (una línea) para IDENTIDAD_JWT_CLAVE_PRIVADA.
# Uso:  echo "IDENTIDAD_JWT_CLAVE_PRIVADA=$(sh infra/generar-clave-jwt.sh)"
set -eu
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
  | openssl pkcs8 -topk8 -nocrypt -outform DER \
  | base64 | tr -d '\n'
echo
