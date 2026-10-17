@echo off
rem Gradle wrapper launcher that uses Android Studio's bundled JDK, so no JAVA_HOME setup is needed.
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "%~dp0gradlew.bat" %*
