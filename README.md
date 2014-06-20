scm2artifact plugin
===================

When using maven 3x for building my own projects, i sometimes try to use libraries available at github.
As I find it annoying to manually clone a git repo, compile the library and then import it into my maven repository,
I have written this plugin.

Prerequisites
-------------

maven 3.1+

Usage
-----

just add the plugin configuration to your project specifying the git url of the project to be built and imported

The plugin will :
- checkout the project
- call mvn clean install on it
```xml
	<repositories>
		<repository>
			<id>scm2artifact-repo</id>
		<url>https://raw.github.com/jrialland/scm2artifact-maven-plugin/mvn-repo</url>
		</repository>
	</repositories>
```
	[...]
```xml
		<plugin>
			<groupId>net.jr</groupId>
			<artifactId>scm2artifact-maven-plugin</artifactId>
			<executions>
				<execution>
					<goals>
						<goal>scm2artifact</goal>
					</goals>
					<configuration>
						<scmUrl>scm:git:https://github.com/FasterXML/jackson-core.git</scmUrl><!-- scm url-->
						<mavenGoal>install</mavenGoal> (<!-- deploy by default -->
					</configuration>
				</execution>
			</executions>
		</plugin>
```