@echo off
setlocal
cd /d "%~dp0"
if not exist "tmp" mkdir "tmp"
if not exist "target\tidescan-0.1.0-SNAPSHOT.jar" (
  echo Build the application first using Maven. See README.md.
  pause
  exit /b 1
)
echo Open http://127.0.0.1:8080/ in your browser.
echo Keep this window open while the game is running.
java -Djava.io.tmpdir=tmp -Xms256m -Xmx2048m -jar target\tidescan-0.1.0-SNAPSHOT.jar --spring.web.resources.static-locations=file:src/main/resources/static/,classpath:/static/
pause
