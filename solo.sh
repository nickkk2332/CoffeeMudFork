#!/bin/bash
# Plays CoffeeMud alone in this terminal window: boots the game with the web,
# email and intermud servers off, listening only on this machine, and connects
# you to it directly.  Typing QUIT in the game (or Ctrl+D) saves and shuts down.
# Server messages go to solo.log.  Any arguments are passed through to the MUD,
# e.g. ./solo.sh BOOT=myother.ini
# Before using this on a UNIX machine, you must 'chmod 755 solo.sh' to make it executable.
java -classpath ".:./lib/js.jar:./lib/jzlib.jar" -Djava.awt.headless=true -Xms65535000 -Xmx115535000 com.planet_ink.coffee_mud.application.SoloMUD "$@"
