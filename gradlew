#!/bin/sh
# Gradle wrapper (lightweight): downloads the distribution on first run.
APP_BASE_NAME=`basename "$0"`
GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
DEFAULT_JVM_OPTS="-Xmx2048m"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
