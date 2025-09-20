# Jenkins EC2 Plugin - Custom WebSocket Edition

## Overview

This is a customized version of the Jenkins EC2 plugin that adds **WebSocket connection support** and **simplified agent naming**. This fork enhances the original plugin with two key features:

1. **WebSocket Connection Strategy**: Option to launch agents using "Launch agent by connecting it to the controller (WebSocket)" instead of SSH
2. **Simplified Agent Names**: EC2 instances display as their instance ID only (e.g., `i-0a92705b5b3437df7`) instead of complex formatted names

## 🚀 New Features

### WebSocket Agent Connection
- **UI Option**: Select "Launch agent by connecting it to the controller (WebSocket)" in the connection strategy dropdown
- **Modern Protocol**: Uses JNLP4-connect with WebSocket support for reliable connections
- **Inbound Agent Support**: Properly configured as inbound agents to eliminate connection errors
- **User Data Integration**: Easy integration with EC2 user data scripts

### Simplified Agent Naming
- **Before**: `EC2 (905418076694) - ubuntu (i-0a92705b5b3437df7)`
- **After**: `i-0a92705b5b3437df7`
- **Benefits**: Cleaner UI, easier identification, direct instance ID reference

## 📋 Requirements

- Jenkins 2.401.3 or later
- Java 11 or 17
- AWS credentials configured in Jenkins
- EC2 instances with internet access to Jenkins controller
- Open port 50000 (JNLP port) for WebSocket connections

## 🛠️ Installation

### Building from Source

```bash
# Clone the repository
git clone [your-repo-url]
cd ec2-plugin

# Build the plugin
mvn clean package -DskipTests

# The plugin file will be generated at:
# target/ec2.hpi
```

### Installing in Jenkins

1. Go to **Manage Jenkins** > **Manage Plugins** > **Advanced**
2. Upload the `ec2.hpi` file
3. Restart Jenkins when prompted

## ⚙️ Configuration

### 1. Configure EC2 Cloud

1. Navigate to **Manage Jenkins** > **Configure System**
2. Add or edit an **Amazon EC2** cloud configuration
3. Configure your AWS credentials and region
4. Set up your **Agent Templates**

### 2. Configure Agent Template for WebSocket

When creating or editing an Agent Template:

1. **Connection Strategy**: Select **"Launch agent by connecting it to the controller (WebSocket)"**
2. **AMI ID**: Choose your AMI (must have Java installed)
3. **Instance Type**: Select appropriate instance type
4. **Security Groups**: Ensure outbound internet access and port 50000 access to Jenkins
5. **User Data**: Include the WebSocket connection script (see below)

### 3. User Data Script

Add this script to your EC2 User Data to automatically connect agents:

```bash
#!/bin/bash

# Update system (optional)
# yum update -y  # For Amazon Linux
# apt-get update -y  # For Ubuntu

# Ensure Java is installed
# yum install -y java-11-openjdk  # For Amazon Linux
# apt-get install -y openjdk-11-jre-headless  # For Ubuntu

# Set up Jenkins agent connection
JENKINS_URL="http://your-jenkins-server:8080"
INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)

# Switch to pcms-builder user and connect to Jenkins
su - pcms-builder -c "
    cd /home/pcms-builder
    wget ${JENKINS_URL}/jnlpJars/agent.jar -O agent.jar
    java -jar agent.jar -url ${JENKINS_URL}/ -name ${INSTANCE_ID} -workDir /home/pcms-builder
"
```

**Important Notes:**
- Replace `your-jenkins-server:8080` with your actual Jenkins URL
- Ensure the `pcms-builder` user exists on your AMI
- The script automatically detects the instance ID for agent naming

## 🔧 Technical Details

### Architecture Changes

#### WebSocket Launcher Implementation
- **File**: `EC2WebSocketLauncher.java` (NEW)
- **Base Class**: Extends `JNLPLauncher` instead of `EC2ComputerLauncher`
- **Protocol**: Uses JNLP4-connect with WebSocket support
- **Method**: Overrides `launch()` method with proper inbound agent setup

#### Connection Strategy Enhancement
- **File**: `ConnectionStrategy.java`
- **Addition**: `WEBSOCKET("Launch agent by connecting it to the controller (WebSocket)")`
- **Integration**: Automatically appears in UI dropdown

#### Agent Naming Simplification
- **File**: `SlaveTemplate.java`
- **Method**: `getSlaveName()` returns instance ID directly
- **Benefit**: Clean, consistent naming convention

### Connection Flow

1. **Agent Creation**: Jenkins creates EC2 instance with WebSocket launcher
2. **Instance Boot**: EC2 instance starts and runs user data script
3. **Agent Download**: Script downloads `agent.jar` from Jenkins
4. **Connection**: Agent connects using modern JNLP protocol with WebSocket
5. **Recognition**: Jenkins recognizes as legitimate inbound agent
6. **Communication**: Bidirectional communication established over WebSocket

## 🐛 Troubleshooting

### Common Issues

#### "is not an inbound agent" Error
**Cause**: Using old plugin version or incorrect launcher type
**Solution**: Ensure you're using the custom WebSocket launcher and proper user data script

#### Agent Not Connecting
**Checklist**:
- ✅ Jenkins URL is accessible from EC2 instance
- ✅ Port 50000 is open for JNLP connections
- ✅ Java is installed on the EC2 instance
- ✅ User data script is executing correctly
- ✅ `pcms-builder` user exists with proper permissions

#### Connection Timeouts
**Solutions**:
- Check security groups (allow outbound HTTPS/HTTP and port 50000)
- Verify Jenkins URL in user data script
- Check EC2 instance logs for script execution errors

### Debugging Commands

```bash
# Check if agent.jar downloaded
ls -la /home/pcms-builder/agent.jar

# Test Jenkins connectivity
curl -I http://your-jenkins-server:8080/

# Check user data script execution
cat /var/log/cloud-init-output.log

# Test JNLP port connectivity
telnet your-jenkins-server 50000
```

## 📊 Comparison with Standard Plugin

| Feature | Standard EC2 Plugin | Custom WebSocket Plugin |
|---------|-------------------|------------------------|
| Connection Method | SSH only | SSH + **WebSocket** |
| Agent Names | Complex format | **Instance ID only** |
| Inbound Support | Limited | **Full support** |
| Modern JNLP | No | **Yes** |
| User Data Integration | Manual setup | **Streamlined** |

## 🔐 Security Considerations

- **Network**: Ensure Jenkins is accessible over HTTPS in production
- **Authentication**: Use proper AWS IAM roles and Jenkins credentials
- **Firewall**: Restrict port 50000 access to necessary sources only
- **User Context**: Agent runs as `pcms-builder` user with limited permissions

## 🤝 Contributing

This is a custom fork. For contributions:

1. Fork this repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## 📄 License

This project maintains the same MIT License as the original Jenkins EC2 plugin.

## 🆘 Support

For issues specific to the WebSocket functionality:
1. Check the troubleshooting section above
2. Review Jenkins logs for connection errors
3. Verify EC2 user data script execution
4. Ensure proper security group configuration

## 🎯 Roadmap

Future enhancements may include:
- [ ] Automatic security group configuration for WebSocket
- [ ] Enhanced logging for connection diagnostics
- [ ] Support for custom user and work directory configuration
- [ ] Integration with Jenkins Configuration as Code (JCasC)

---

**Built with ❤️ for reliable Jenkins-EC2 integration**

*Last updated: September 19, 2025*