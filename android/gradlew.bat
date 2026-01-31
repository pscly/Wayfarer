@rem
@rem  Gradle startup script for Windows
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Set local scope for the variables with windows NT shell
@rem
@rem ##########################################################################

setlocal

set APP_HOME=%~dp0

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
goto execute

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe

:execute
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%~n0" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
endlocal
