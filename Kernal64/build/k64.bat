@echo off
set HOME=%~dp0
set LIB=%HOME%lib
set CP=%LIB%\kernal64.jar;%LIB%\jinput.jar;%LIB%\scala-library.jar;%LIB%\scala-parser-combinators_2.13-1.1.2.jar;%LIB%\commons-net-3.3.jar;%LIB%\jsoup-1.13.1.jar
start javaw -server -Xms64M -Xmx128M -cp %CP% -Djava.library.path=%LIB% ucesoft.cbm.c64.C64 %*
