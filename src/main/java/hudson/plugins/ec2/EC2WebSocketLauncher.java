/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@link hudson.slaves.ComputerLauncher} for EC2 that uses WebSocket (JNLP) connection.
 * 
 * This launcher configures the EC2 instance to connect back to the Jenkins controller
 * using WebSocket protocol instead of SSH. The agent will download the remoting.jar
 * and connect using JNLP.
 *
 * @author GitHub Copilot
 */
public class EC2WebSocketLauncher extends JNLPLauncher {
    private static final Logger LOGGER = Logger.getLogger(EC2WebSocketLauncher.class.getName());
    
    public EC2WebSocketLauncher() {
        // Enable WebSocket and tunnel for EC2 connections
        super(true); // true enables WebSocket
    }

    @Override
    public boolean isLaunchSupported() {
        // WebSocket launcher supports launching
        return true;
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        // Handle cleanup after WebSocket disconnection
        final PrintStream logger = listener.getLogger();
        LOGGER.info("WebSocket agent disconnected: " + computer.getName());
        logger.println("WebSocket agent disconnected. Ready for reconnection.");
        super.afterDisconnect(computer, listener);
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        final PrintStream logger = listener.getLogger();
        
        LOGGER.info("WebSocket launcher launching agent: " + computer.getName());
        logger.println("🚀 WebSocket launcher selected for agent: " + computer.getName());
        
        // Setup the connection using the Jenkins controller URL
        String jenkinsUrl = Jenkins.get().getRootUrl();
        if (jenkinsUrl == null) {
            String errorMsg = "ERROR: Jenkins URL is not configured. Please set Jenkins URL in Jenkins configuration.";
            LOGGER.severe(errorMsg);
            logger.println(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // Ensure Jenkins URL ends with slash
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        
        logger.println("✅ Jenkins URL: " + jenkinsUrl);
        
        // Generate the JNLP URL for the agent
        String jnlpUrl = generateAgentCommand(computer);
        if (jnlpUrl != null) {
            logger.println("✅ Agent JNLP URL: " + jnlpUrl);
            logger.println("📋 EC2 instance should connect using:");
            logger.println("   java -jar agent.jar -url " + jenkinsUrl + " -name " + computer.getName() + " -workDir /home/pcms-builder");
        }
        
        logger.println("🔧 Configuring WebSocket agent for inbound connection...");
        
        try {
            // Call the parent JNLPLauncher to set up the inbound agent properly
            // This should complete quickly and not block EC2 instance creation
            super.launch(computer, listener);
            
            LOGGER.info("WebSocket launcher configured successfully for " + computer.getName());
            logger.println("✅ WebSocket launcher configured successfully.");
            logger.println("⏳ Waiting for EC2 instance to start and connect...");
            logger.println("🌐 Agent will connect using WebSocket to: " + jenkinsUrl);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during WebSocket launcher setup for " + computer.getName(), e);
            logger.println("❌ Error: " + e.getMessage());
            // Re-throw to prevent EC2 instance creation with broken launcher
            throw new RuntimeException("WebSocket launcher setup failed", e);
        }
    }
    
    /**
     * Generates the JNLP URL that should be used by the EC2 instance to connect back
     * to Jenkins using WebSocket.
     */
    public String generateAgentCommand(SlaveComputer computer) {
        String jenkinsUrl = Jenkins.get().getRootUrl();
        if (jenkinsUrl == null) {
            return null;
        }
        
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        
        String agentName = computer.getName();
        
        // Return the JNLP URL that your AMI script can use
        return jenkinsUrl + "computer/" + agentName + "/jenkins-agent.jnlp";
    }
    
    /**
     * Returns the JNLP URL for WebSocket agent connection.
     * This URL can be used by your AMI script to connect the agent.
     */
    public static String getWebSocketAgentUrl(String jenkinsUrl, String agentName) {
        if (jenkinsUrl == null || agentName == null) {
            return null;
        }
        
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        
        return jenkinsUrl + "computer/" + agentName + "/jenkins-agent.jnlp";
    }
    
    /**
     * Generates the complete agent command for the specified user and work directory.
     */
    public static String getWebSocketAgentCommand(String jenkinsUrl, String agentName, String workDir, String user) {
        if (jenkinsUrl == null || agentName == null) {
            return null;
        }
        
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        
        workDir = workDir != null ? workDir : "/home/pcms-builder";
        user = user != null ? user : "pcms-builder";
        
        return String.format(
            "#!/bin/bash\n" +
            "# Run as user %s\n" +
            "su - %s -c \"cd %s && wget %sjnlpJars/agent.jar -O agent.jar && java -jar agent.jar -url %s -name %s -workDir %s\"",
            user, user, workDir, jenkinsUrl, jenkinsUrl, agentName, workDir
        );
    }
}