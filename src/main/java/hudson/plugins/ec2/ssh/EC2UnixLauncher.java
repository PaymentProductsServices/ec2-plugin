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
package hudson.plugins.ec2.ssh;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2Readiness;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * {@link ComputerLauncher} that connects to a Unix agent on EC2 by using local SSH client.
 * This implementation uses the system's SSH command instead of Java SSH libraries
 * to avoid potential networking issues with Trilead SSH.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2UnixLauncher extends LocalSSHLauncher {

    private static final Logger LOGGER = Logger.getLogger(EC2UnixLauncher.class.getName());

    private static final String READINESS_SLEEP_MS = "jenkins.ec2.readinessSleepMs";
    private static final String READINESS_TRIES = "jenkins.ec2.readinessTries";

    private static int readinessSleepMs = 1000;
    private static int readinessTries = 120;

    static {
        String prop = System.getProperty(READINESS_TRIES);
        if (prop != null) {
            readinessTries = Integer.parseInt(prop);
        }
        prop = System.getProperty(READINESS_SLEEP_MS);
        if (prop != null) {
            readinessSleepMs = Integer.parseInt(prop);
        }
    }

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener)
            throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        EC2AbstractSlave node = computer.getNode();
        SlaveTemplate template = computer.getSlaveTemplate();

        if (node == null) {
            throw new IllegalStateException("EC2 node is null");
        }

        if (template == null) {
            throw new IOException("Could not find corresponding agent template for " + computer.getDisplayName());
        }

        // Check readiness for nodes that support it
        if (node instanceof EC2Readiness readinessNode) {
            int tries = readinessTries;

            while (tries-- > 0) {
                if (readinessNode.isReady()) {
                    break;
                }

                logInfo(computer, listener, "Node still not ready. Current status: " + readinessNode.getEc2ReadinessStatus());
                Thread.sleep(readinessSleepMs);
            }

            if (!readinessNode.isReady()) {
                throw new IOException("Node still not ready, timed out after " + (readinessTries * readinessSleepMs / 1000)
                        + "s with status " + readinessNode.getEc2ReadinessStatus());
            }
        }

        logInfo(computer, listener, "Launching Unix EC2 instance using local SSH: " + node.getInstanceId());

        // Bootstrap SSH connection using local SSH client
        boolean isBootstrapped = bootstrap(computer, listener, template);
        if (!isBootstrapped) {
            logWarning(computer, listener, "SSH bootstrap failed");
            return;
        }

        // Apply boot delay if configured
        int bootDelay = node.getBootDelay();
        if (bootDelay > 0) {
            logInfo(computer, listener, "SSH service responded. Waiting " + bootDelay + "ms for service to stabilize");
            Thread.sleep(bootDelay);
            logInfo(computer, listener, "SSH service should have stabilized");
        }

        // Prepare the Unix instance
        prepareUnixInstance(computer, listener, node);

        // Launch the Jenkins agent
        launchJenkinsAgent(computer, listener, node);
    }

    /**
     * Prepare the Unix instance for Jenkins agent
     */
    private void prepareUnixInstance(EC2Computer computer, TaskListener listener, EC2AbstractSlave node) 
            throws IOException, InterruptedException {
        
        String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");
        String javaPath = node.javaPath;
        
        logInfo(computer, listener, "Preparing Unix instance for Jenkins agent");
        
        // Create temporary directory
        executeSSHCommand(computer, listener, "mkdir -p " + tmpDir, 30);
        
        // Execute init script if provided
        String initScript = node.initScript;
        if (initScript != null && !initScript.trim().isEmpty()) {
            logInfo(computer, listener, "Checking if init script needs to be executed");
            
            ProcessResult checkInit = executeSSHCommand(computer, listener, "test -e ~/.hudson-run-init", 30);
            if (checkInit.exitCode != 0) {
                logInfo(computer, listener, "Executing init script");
                
                // Create init script file
                String createScript = String.format("cat > %s/init.sh << 'EOF'\n%s\nEOF", tmpDir, initScript);
                executeSSHCommand(computer, listener, createScript, 60);
                
                // Make it executable and run
                executeSSHCommand(computer, listener, "chmod +x " + tmpDir + "/init.sh", 30);
                ProcessResult initResult = executeSSHCommand(computer, listener, 
                    buildUpCommand(computer, tmpDir + "/init.sh"), 300);
                
                if (initResult.exitCode == 0) {
                    executeSSHCommand(computer, listener, "touch ~/.hudson-run-init", 30);
                    logInfo(computer, listener, "Init script executed successfully");
                } else {
                    throw new IOException("Init script failed with exit code: " + initResult.exitCode);
                }
            } else {
                logInfo(computer, listener, "Init script already executed (found ~/.hudson-run-init)");
            }
        }
        
        // Ensure Java is available
        ProcessResult javaCheck = executeSSHCommand(computer, listener, javaPath + " -version", 30);
        if (javaCheck.exitCode != 0) {
            logInfo(computer, listener, "Java not found at " + javaPath + ", attempting to install");
            
            // Try to install Java
            String installJava = "sudo amazon-linux-extras install java-openjdk11 -y || " +
                                "sudo yum install -y fontconfig java-11-openjdk || " +
                                "sudo apt-get update && sudo apt-get install -y openjdk-11-jdk";
            executeSSHCommand(computer, listener, installJava, 300);
            
            // Verify Java installation
            ProcessResult javaVerify = executeSSHCommand(computer, listener, "java -version", 30);
            if (javaVerify.exitCode != 0) {
                throw new IOException("Failed to install or find Java on the instance");
            }
        }
        
        // Ensure SSH client tools are available
        ProcessResult scpCheck = executeSSHCommand(computer, listener, "which scp", 30);
        if (scpCheck.exitCode != 0) {
            logInfo(computer, listener, "SCP not found, installing SSH client tools");
            String installSsh = "sudo yum install -y openssh-clients || " +
                               "sudo apt-get update && sudo apt-get install -y openssh-client";
            executeSSHCommand(computer, listener, installSsh, 300);
        }
        
        // Copy remoting.jar to the instance
        logInfo(computer, listener, "Copying remoting.jar to: " + tmpDir);
        copyRemotingJar(computer, listener, tmpDir);
    }
    
    /**
     * Copy Jenkins remoting.jar to the instance
     */
    private void copyRemotingJar(EC2Computer computer, TaskListener listener, String tmpDir) 
            throws IOException, InterruptedException {
        
        // Get Jenkins master URL
        String jenkinsUrl = getJenkinsUrl();
        String remotingUrl = jenkinsUrl + "/jnlpJars/remoting.jar";
        
        // Download remoting.jar using curl or wget
        String downloadCommand = String.format(
            "curl -L -o %s/remoting.jar '%s' || wget -O %s/remoting.jar '%s'", 
            tmpDir, remotingUrl, tmpDir, remotingUrl);
        
        ProcessResult downloadResult = executeSSHCommand(computer, listener, downloadCommand, 300);
        
        if (downloadResult.exitCode != 0) {
            throw new IOException("Failed to download remoting.jar: " + downloadResult.output);
        }
        
        // Verify the file was downloaded
        ProcessResult verifyResult = executeSSHCommand(computer, listener, "ls -la " + tmpDir + "/remoting.jar", 30);
        if (verifyResult.exitCode != 0) {
            throw new IOException("remoting.jar not found after download");
        }
        
        logInfo(computer, listener, "remoting.jar downloaded successfully");
    }

    /**
     * Launch the Jenkins agent
     */
    private void launchJenkinsAgent(EC2Computer computer, TaskListener listener, EC2AbstractSlave node) 
            throws IOException, InterruptedException {
        
        String tmpDir = (Util.fixEmptyAndTrim(node.tmpDir) != null ? node.tmpDir : "/tmp");
        String javaPath = node.javaPath;
        String jvmOpts = node.jvmopts != null ? node.jvmopts : "";
        String prefix = computer.getSlaveCommandPrefix() != null ? computer.getSlaveCommandPrefix() : "";
        String suffix = computer.getSlaveCommandSuffix() != null ? computer.getSlaveCommandSuffix() : "";
        String remoteFS = node.getRemoteFS();
        String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
        
        // Build the launch command - this should create a persistent SSH connection
        String launchCommand = String.format("%s %s %s -jar %s/remoting.jar -workDir %s %s",
            prefix.trim(), javaPath, jvmOpts, tmpDir, workDir, suffix.trim()).trim();
        
        logInfo(computer, listener, "Launching Jenkins agent with command: " + launchCommand);
        
        // Create persistent SSH connection for the agent (non-background)
        SlaveTemplate template = computer.getSlaveTemplate();
        String host = getHostAddress(computer, template);
        String user = computer.getRemoteAdmin();
        File keyFile = createIdentityKeyFile(computer);
        
        try {
            List<String> sshCommand = buildSSHCommand(host, user, keyFile, launchCommand, template);
            
            logInfo(computer, listener, "Creating persistent SSH connection: " + String.join(" ", sshCommand));
            
            // Use CommandLauncher to create persistent connection like the original implementation
            hudson.slaves.CommandLauncher commandLauncher = 
                new hudson.slaves.CommandLauncher(String.join(" ", sshCommand), null);
            commandLauncher.launch(computer, listener);
            
        } finally {
            // Note: Don't delete keyFile here as the persistent connection needs it
        }
    }

    /**
     * Get Jenkins master URL
     */
    private String getJenkinsUrl() {
        String rootUrl = Jenkins.get().getRootUrl();
        if (rootUrl != null && !rootUrl.isEmpty()) {
            return rootUrl.endsWith("/") ? rootUrl.substring(0, rootUrl.length() - 1) : rootUrl;
        }
        return "http://localhost:8080"; // fallback
    }

    protected String buildUpCommand(EC2Computer computer, String command) {
        // For Unix systems, we can run commands as-is, but may need sudo for some operations
        SlaveTemplate template = computer.getSlaveTemplate();
        String defaultAdmin = "root";
        
        if (template != null && !template.isWindowsSlave()) {
            String remoteAdmin = computer.getRemoteAdmin();
            if (!"root".equals(remoteAdmin)) {
                // If not running as root, prefix with sudo for system commands
                if (command.contains("yum ") || command.contains("apt-get ") || command.startsWith("/")) {
                    return "sudo " + command;
                }
            }
        }
        
        return command;
    }
}
