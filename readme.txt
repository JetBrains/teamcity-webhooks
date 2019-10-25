
 TeamCity server-side plugin

 This is an empty project to develop TeamCity plugin that operates on server-side only.

 1. Implement
 Put your implementing classes to "<artifactId>-server" module. Do not forget to update spring context file in 'main/resources/META-INF'. See TeamCity documentation for details.

 2. Build
 Issue 'mvn package' command from the root project to build your plugin. Resulting package <artifactId>.zip will be placed in 'target' directory. 
 
 3. Install
 To install the plugin, put zip archive to 'plugins' dir under TeamCity data directory and restart the server.

 4. Configuration
 The plugin can be configured via following Project parameters (in general and for agent events Root Project should be used):
    - teamcity.internal.webhooks.enable : flag indicates web hooks functionality is enabled
        Possible values: true, false
    - teamcity.internal.webhooks.events : list of events whose occurrence will trigger web hooks sending.
        Possible values: AGENT_REGISTRED, AGENT_UNREGISTERED, AGENT_REMOVED, BUILD_STARTED, BUILD_FINISHED, BUILD_INTERRUPTED. Should be separated by ";"
    - teamcity.internal.webhooks.url : url to send web hooks by HTTP POST request
    Http Basic Auth parameters:
    - teamcity.internal.webhooks.username : username
    - teamcity.internal.webhooks.password : password (keep in password type parameter)

    Advanced parameters:
    - teamcity.internal.webhooks.retry_count : count of retry which will be performed in case of exception thrown during the web hook request or unsuccessful HTTP response code (not 2**)
        Default value is 0.

 
