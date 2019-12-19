# teamcity-webhooks

TeamCity server-side plugin that allows you to send WebHooks asynchronously to third-party systems.

[![official project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![Build Status](https://teamcity.jetbrains.com/app/rest/builds/buildType:(id:TeamCityPluginsByJetBrains_TeamcityWebhooks_Build)/statusIcon.svg)](https://teamcity.jetbrains.com/viewType.html?buildTypeId=TeamCityPluginsByJetBrains_TeamcityWebhooks_Build)

## Getting Started

Download links:

SSH clone URL: ssh://git@git.jetbrains.team/teamcity-webhooks.git

HTTPS clone URL: https://git.jetbrains.team/teamcity-webhooks.git



These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

## Build

 Issue 'mvn package' command from the root project to build the plugin. Resulting package teamcity-webhooks.zip will be placed in 'target' directory. 

```
mvn package
```

## Install

 To install the plugin, put zip archive to 'plugins' dir under TeamCity data directory and restart the server. 

## Configuration

 The plugin can be configured via following project parameters (for agent events ```<Root Project>``` should be used):
 -  ```teamcity.internal.webhooks.enable ```   - flag indicates web hooks functionality is enabled.
        Possible values: ```true```, ```false```.
 -  ``` teamcity.internal.webhooks.events ``` - list of events whose occurrence will trigger web hooks sending.
        Possible values: ```AGENT_REGISTRED```, ```AGENT_UNREGISTERED```, ```AGENT_REMOVED```, ```BUILD_STARTED```, ```BUILD_FINISHED```, ```BUILD_INTERRUPTED```, ```CHANGES_LOADED```, ```BUILD_TYPE_ADDED_TO_QUEUE```, ```BUILD_PROBLEMS_CHANGED```. 
        Should be separated by ```;```.
 - ```teamcity.internal.webhooks.url``` : url to send web hooks by HTTP POST request
 - ```teamcity.internal.webhooks.{event_name}.fields ```: list of fields are supposed to be return in web hook for specific event in Team City REST-API format.
        Example value:  
       
       teamcity.internal.webhooks.BUILD_STARTED.fields =
               fields=id,buildTypeId,number,running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds,leftSeconds,currentStageText)
                       
   ####Http Basic Auth parameters:
 -  ```teamcity.internal.webhooks.username ``` - username.
 -  ```teamcity.internal.webhooks.password ``` - password (keep in password type parameter).
    
    ####Advanced parameters:
 - ```teamcity.internal.webhooks.retry_count``` - count of retry which will be performed in case of exception thrown during the web hook request or unsuccessful HTTP response code (not 2**)
        Default value is 0.
