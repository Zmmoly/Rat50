#!/bin/bash

echo "Downloading Gradle Wrapper JAR..."

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.2.1/gradle/wrapper/gradle-wrapper.jar"

# Create directory if it doesn't exist
mkdir -p gradle/wrapper

# Try to download with curl
if command -v curl &> /dev/null; then
    echo "Using curl..."
    curl -L -o "$WRAPPER_JAR" "$WRAPPER_URL"
    if [ $? -eq 0 ]; then
        echo "✅ Successfully downloaded gradle-wrapper.jar"
        ls -lh "$WRAPPER_JAR"
        exit 0
    fi
fi

# Try to download with wget
if command -v wget &> /dev/null; then
    echo "Using wget..."
    wget -O "$WRAPPER_JAR" "$WRAPPER_URL"
    if [ $? -eq 0 ]; then
        echo "✅ Successfully downloaded gradle-wrapper.jar"
        ls -lh "$WRAPPER_JAR"
        exit 0
    fi
fi

echo "❌ Failed to download gradle-wrapper.jar"
echo "Please download it manually from:"
echo "$WRAPPER_URL"
exit 1
