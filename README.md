batch-dem-reader
=================

This project will build on all branches, releases to GitHub package manager must be triggered via the CircleCI API.


### Releasing a new version

This project is released via GitHub package manager to https://maven.pkg.github.com/komoot/batch-dem-reader 
The required access token to publish is defined in the project environment and configured using the included Maven `build-settings.xml` file.

 
```xml
<server>
    <id>github-batch-dem-reader</id>
    <username>${env.GITHUB_USER}</username>
    <password>${env.GITHUB_TOKEN}</password>
</server>
```

In order to publish a new version the injected env vars must have GitHub package read and write access. These env vars are provided in the backend context.


#### Performing a release

To perform a release you first need your personal CircleCI access token.

##### Finding your CircleCI Token

In order to access the CircleCI API you need a personal access token. This token has access to all repositories you have access to so it is important not to share it!
You can manage tokens in your CircleCI account under User Settings, Personal API Tokens [https://circleci.com/account/api](https://circleci.com/account/api).

##### API Call

```
curl -X POST -H "Content-Type: application/json" -d '{
       "parameters": {
           "version": "{RELEASE VERSION} 1.82",
           "next": "{NEXT VERSION} 1.83-SNAPSHOT",
           "release": true
       }
   }' "https://circleci.com/api/v2/project/gh/komoot/batch-dem-reader/pipeline?circle-token={YOUR TOKEN}"

```

This will trigger the release workflow. After it completes successfully a new version corresponding with the input `version` filed will be
available in GitHub package manager. 

https://github.com/komoot/batch-dem-reader/packages

#### Importing a release

In order to import this project as a Maven dependency you must configure both your `~/.m2/settings.xml` file 
as well as your projects `repositories`.

#### Personal .m2 settings

You first need to create a GitHub personal access token with SSO access to the Komoot organization. Using this token you can
read packages from this repository. You can create a token here: https://github.com/settings/tokens

With this token you can update your `settings.xml` file to include GitHub package manager as a server.

```xml
<servers>
    <server>
        <id>github-batch-dem-reader</id>
        <username>{YOUR GITHUB USERNAME}</username>
        <password>{YOUR GITHUB ACCESS TOKEN}</password>
    </server>
</servers>
```

### Project repository

You can now add package manager as a repository in your project.

```xml
<repository>
    <id>github-batch-dem-reader</id>
    <name>github-batch-dem-reader</name>
    <url>https://maven.pkg.github.com/komoot/batch-dem-reader</url>
</repository>
```  

And the project can be imported normally.

```
<groupId>de.komoot.batch-dem-reader</groupId>
```