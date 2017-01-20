# CachingClassLoader [![Build Status](https://jenkins.nallar.me/job/CachingClassLoader/job/master/badge/icon)](https://jenkins.nallar.me/job/CachingClassLoader/job/master/)

Improves server startup time by caching transformed classes from previous startups

# Usage

1. Download the latest version from [Jenkins](https://jenkins.nallar.me/job/CachingClassLoader/job/master/)
2. Place the jar in your server directory
3. Change your startup script to use the caching class loader jar, add a `--serverJar=<server_jar_name>` parameter

Example:  
`java -jar CachingClassLoader-0.1-dev.jar --serverJar=forge-1.10.2-12.18.3.2185-universal.jar`

# Known issues

The cache is not cleared when mod configuration changes (as this would cause it to clear on every restart, making it pointless).
If you change a configuration which affects the behaviour of a transformer, you must clear the cache manually by deleting the CachingClassLoader folder in the server directory
