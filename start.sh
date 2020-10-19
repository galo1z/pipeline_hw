#!/bin/bash
cd /home/webgoat/
java -Dfile.encoding=UTF-8 -jar webgoat.jar --server.address=0.0.0.0 > webgoat.log &
tail -300f webgoat.log
