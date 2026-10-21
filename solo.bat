REM Plays CoffeeMud alone in this console window: boots the game with the web,
REM email and intermud servers off, listening only on this machine, and connects
REM you to it directly.  Typing QUIT in the game (or Ctrl+Z, Enter) saves and shuts down.
REM Server messages go to solo.log.  Any arguments are passed through to the MUD,
REM e.g. solo.bat BOOT=myother.ini
REM You may need to modify the word java here to point to your java.exe with a proper path.
java -classpath ".;.\lib\js.jar;.\lib\jzlib.jar" -Djava.awt.headless=true -Xms128m -Xmx256m com.planet_ink.coffee_mud.application.SoloMUD %*
