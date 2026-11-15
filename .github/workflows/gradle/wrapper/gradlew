#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

app_name="Gradle Wrapper"
app_home=$(dirname "$(unset CDPATH; cd -- "$(dirname "$0")" && pwd -P)")

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/bin/java" ] ; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=$(ulimit -H -n 2>/dev/null)
    if [ $? -eq 0 ] ; then
        [ "$MAX_FD_LIMIT" -gt 65536 ] && MAX_FD_LIMIT=65536
        ulimit -n $MAX_FD_LIMIT 2>/dev/null
    fi
fi

# Setup JVM options
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
JAVA_OPTS="$JAVA_OPTS $DEFAULT_JVM_OPTS"

exec "$JAVACMD" $JAVA_OPTS \
    -classpath "$app_home/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
