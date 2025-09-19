package hudson.plugins.ec2.ssh;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.util.KeyPair;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import software.amazon.awssdk.services.ec2.model.Instance;

/**
 * SSH Launcher that uses the local system's SSH client instead of Java SSH libraries.
 * This approach bypasses potential networking issues with Java SSH implementations.
 */
public abstract class LocalSSHLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(LocalSSHLauncher.class.getName());

    private static final String BOOTSTRAP_AUTH_SLEEP_MS = "jenkins.ec2.bootstrapAuthSleepMs";
    private static final String BOOTSTRAP_AUTH_TRIES = "jenkins.ec2.bootstrapAuthTries";

    private static int bootstrapAuthSleepMs = 30000;
    private static int bootstrapAuthTries = 30;

    static {
        String prop = System.getProperty(BOOTSTRAP_AUTH_SLEEP_MS);
        if (prop != null) {
            bootstrapAuthSleepMs = Integer.parseInt(prop);
        }
        prop = System.getProperty(BOOTSTRAP_AUTH_TRIES);
        if (prop != null) {
            bootstrapAuthTries = Integer.parseInt(prop);
        }
    }

    protected void log(Level level, EC2Computer computer, TaskListener listener, String message) {
        EC2Cloud.log(LOGGER, level, listener, message);
    }

    protected void logException(EC2Computer computer, TaskListener listener, String message, Throwable exception) {
        EC2Cloud.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(EC2Computer computer, TaskListener listener, String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(EC2Computer computer, TaskListener listener, String message) {
        log(Level.WARNING, computer, listener, message);
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer) slaveComputer;
            launchScript(computer, listener);
        } catch (Exception e) {
            e.printStackTrace(listener.error(e.getMessage()));
            if (slaveComputer.getNode() instanceof EC2AbstractSlave ec2AbstractSlave) {
                LOGGER.log(Level.FINE, String.format(
                    "Terminating the ec2 agent %s due a problem launching or connecting to it",
                    slaveComputer.getName()), e);
                ec2AbstractSlave.terminate();
            }
        }
    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launchScript(EC2Computer computer, TaskListener listener)
            throws IOException, InterruptedException;

    /**
     * Creates a temporary private key file for SSH authentication
     */
    protected File createIdentityKeyFile(EC2Computer computer) throws IOException {
        KeyPair keyPair = computer.getCloud().getKeyPair();
        if (keyPair == null) {
            throw new IOException("No key pair available for EC2 instance");
        }

        File tempFile = Files.createTempFile("ec2_ssh_", ".pem").toFile();
        tempFile.deleteOnExit();

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(keyPair.getMaterial());
            writer.flush();
            
            // Set restrictive permissions (equivalent to chmod 600)
            tempFile.setReadable(false, false);
            tempFile.setWritable(false, false);
            tempFile.setExecutable(false, false);
            tempFile.setReadable(true, true);
            tempFile.setWritable(true, true);
            
            return tempFile;
        } catch (Exception e) {
            tempFile.delete();
            throw new IOException("Error creating temporary identity key file", e);
        }
    }

    /**
     * Execute SSH command using the local SSH client
     */
    protected ProcessResult executeSSHCommand(EC2Computer computer, TaskListener listener, 
                                            String command, int timeoutSeconds) throws IOException, InterruptedException {
        
        SlaveTemplate template = computer.getSlaveTemplate();
        String host = getHostAddress(computer, template);
        String user = computer.getRemoteAdmin();
        File keyFile = createIdentityKeyFile(computer);
        
        try {
            List<String> sshCommand = buildSSHCommand(host, user, keyFile, command, template);
            
            logInfo(computer, listener, "Executing SSH command: " + String.join(" ", sshCommand));
            
            ProcessBuilder pb = new ProcessBuilder(sshCommand);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    listener.getLogger().println(line);
                }
            }
            
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("SSH command timed out after " + timeoutSeconds + " seconds");
            }
            
            return new ProcessResult(process.exitValue(), output.toString());
            
        } finally {
            if (keyFile != null && keyFile.exists()) {
                keyFile.delete();
            }
        }
    }

    /**
     * Build SSH command with appropriate options
     */
    protected List<String> buildSSHCommand(String host, String user, File keyFile, String command, SlaveTemplate template) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        
        // Connection options with improved keepalive settings
        cmd.add("-o");
        cmd.add("ConnectTimeout=30");
        
        cmd.add("-o");
        cmd.add("ServerAliveInterval=30");
        
        cmd.add("-o");
        cmd.add("ServerAliveCountMax=10");
        
        cmd.add("-o");
        cmd.add("TCPKeepAlive=yes");
        
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        
        cmd.add("-o");
        cmd.add("PasswordAuthentication=no");
        
        // Skip host key validation if configured
        if (template != null && template.connectionStrategy != null) {
            cmd.add("-o");
            cmd.add("StrictHostKeyChecking=no");
            cmd.add("-o");
            cmd.add("UserKnownHostsFile=/dev/null");
        }
        
        // Private key
        if (keyFile != null) {
            cmd.add("-i");
            cmd.add(keyFile.getAbsolutePath());
        }
        
        // User and host
        cmd.add(user + "@" + host);
        
        // Command to execute
        if (command != null && !command.trim().isEmpty()) {
            cmd.add(command);
        }
        
        return cmd;
    }

    /**
     * Get the host address for SSH connection
     */
    protected String getHostAddress(EC2Computer computer, SlaveTemplate template) throws IOException, InterruptedException {
        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            throw new IOException("EC2 node is null");
        }

        Instance instance = computer.updateInstanceDescription();
        if (instance == null) {
            throw new IOException("EC2 instance not found");
        }

        ConnectionStrategy strategy = template != null ? template.connectionStrategy : ConnectionStrategy.PUBLIC_IP;
        
        return EC2HostAddressProvider.unix(instance, strategy);
    }

    /**
     * Test SSH connectivity
     */
    protected boolean testSSHConnection(EC2Computer computer, TaskListener listener) {
        try {
            ProcessResult result = executeSSHCommand(computer, listener, "echo 'SSH connection test'", 30);
            return result.exitCode == 0;
        } catch (IOException | InterruptedException e) {
            logException(computer, listener, "SSH connection test failed", e);
            return false;
        }
    }

    /**
     * Bootstrap authentication using local SSH client
     */
    protected boolean bootstrap(EC2Computer computer, TaskListener listener, SlaveTemplate template)
            throws IOException, InterruptedException {
        
        logInfo(computer, listener, "Starting SSH bootstrap with local SSH client");
        
        int tries = bootstrapAuthTries;
        while (tries-- > 0) {
            logInfo(computer, listener, "Testing SSH connection (attempt " + (bootstrapAuthTries - tries) + "/" + bootstrapAuthTries + ")");
            
            if (testSSHConnection(computer, listener)) {
                logInfo(computer, listener, "SSH connection successful");
                return true;
            }
            
            if (tries > 0) {
                logInfo(computer, listener, "SSH connection failed, retrying in " + (bootstrapAuthSleepMs / 1000) + " seconds...");
                Thread.sleep(bootstrapAuthSleepMs);
            }
        }
        
        logWarning(computer, listener, "SSH bootstrap failed after " + bootstrapAuthTries + " attempts");
        return false;
    }

    /**
     * Result of a process execution
     */
    protected static class ProcessResult {
        final int exitCode;
        final String output;
        
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}