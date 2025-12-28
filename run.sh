#!/bin/bash

PORT=${PORT:-8080}
CAPACITY=${CAPACITY:-100}

JAR="target/flash-kv.jar"

if [ ! -f "$JAR" ]; then
  echo "JAR not found. Run mvn clean package first."
  exit 1
fi

echo "Starting FlashKV on port $PORT with capacity $CAPACITY"
exec java -jar "$JAR" "$PORT" "$CAPACITY"
