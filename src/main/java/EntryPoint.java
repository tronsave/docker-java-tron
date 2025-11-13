import java.io.*;
import java.nio.file.*;
import java.util.*;

public class EntryPoint {
    // Default configuration values
    private static String configFile = "/etc/tron/mainnet_config.conf";
    private static int configP2pPort = 28888;
    private static int configFullNodePort = 8090;
    private static String configSolidityNodePort = null;
    private static double configVmMaxTimeRatio = 20.0;
    private static int rpcFullNode = 8545;
    private static int rpcSolidityNode = 8555;
    
    // Event plugin defaults (mainnet)
    private static boolean configEventPluginEnabled = true;
    private static String configEventPluginPath = "";
    private static String configEventPluginKafkaServer = "kafka:9092";
    private static boolean configBlockTriggerEnabled = true;
    private static boolean configTransactionTriggerEnabled = true;
    private static boolean configContracteventTriggerEnabled = true;
    private static boolean configContractlogTriggerEnabled = false;
    private static boolean configSolidityBlockTriggerEnabled = false;
    private static boolean configSolidityEventTriggerEnabled = false;
    private static boolean configSolidityLogTriggerEnabled = false;
    private static String configContractAddressFilter = "\"\"";
    private static String configContractTopicFilter = "\"\"";
    
    private static String esFlag = "";
    private static String witnessFlag = "";
    
    private static void validateBoolean(String varName, String varValue) {
        if (varValue != null && !varValue.isEmpty() && 
            !varValue.equals("true") && !varValue.equals("false")) {
            System.err.println("Invalid " + varName + ": " + varValue + ". Must be one of: \"true\", \"false\"");
            System.exit(1);
        }
    }
    
    private static String buildFilter(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] items = value.split("\\s+");
        if (items.length == 0) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i == items.length - 1) {
                result.append("\"").append(items[i]).append("\"");
            } else {
                result.append("\"").append(items[i]).append("\",\n      ");
            }
        }
        return result.toString();
    }
    
    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }
    
    private static String getEnv(String name) {
        return System.getenv(name);
    }
    
    public static void main(String[] args) {
        try {
            // Validate network
            String network = getEnv("NETWORK");
            if (network != null && !network.isEmpty() && 
                !network.equals("mainnet") && !network.equals("nile")) {
                System.err.println("Invalid NETWORK: " + network + ". Must be one of: \"mainnet\", \"nile\"");
                System.exit(1);
            }
            
            // Set config file based on network
            if ("nile".equals(network)) {
                configFile = "/etc/tron/nile_config.conf";
            }
            
            // Validate and set witness mode
            String witnessMode = getEnv("WITNESS_MODE");
            validateBoolean("WITNESS_MODE", witnessMode);
            if ("true".equals(witnessMode)) {
                witnessFlag = "--witness";
            }
            
            // Validate and set event plugin
            String eventPluginEnabled = getEnv("EVENT_PLUGIN_ENABLED");
            validateBoolean("EVENT_PLUGIN_ENABLED", eventPluginEnabled);
            if (eventPluginEnabled != null && !eventPluginEnabled.isEmpty()) {
                configEventPluginEnabled = Boolean.parseBoolean(eventPluginEnabled);
            }
            
            // Override ports and settings from environment variables
            String p2pPort = getEnv("P2P_PORT");
            if (p2pPort != null && !p2pPort.isEmpty()) {
                configP2pPort = Integer.parseInt(p2pPort);
            }
            
            String fullNodePort = getEnv("FULL_NODE_PORT");
            if (fullNodePort != null && !fullNodePort.isEmpty()) {
                configFullNodePort = Integer.parseInt(fullNodePort);
            }
            
            String solidityNodePort = getEnv("SOLIDITY_NODE_PORT");
            if (solidityNodePort != null && !solidityNodePort.isEmpty()) {
                configSolidityNodePort = solidityNodePort;
            }
            
            String vmMaxTimeRatio = getEnv("VM_MAX_TIME_RATIO");
            if (vmMaxTimeRatio != null && !vmMaxTimeRatio.isEmpty()) {
                configVmMaxTimeRatio = Double.parseDouble(vmMaxTimeRatio);
            }
            
            String rpcFullNodeStr = getEnv("RPC_FULL_NODE");
            if (rpcFullNodeStr != null && !rpcFullNodeStr.isEmpty()) {
                rpcFullNode = Integer.parseInt(rpcFullNodeStr);
            }
            
            String rpcSolidityNodeStr = getEnv("RPC_SOLIDITY_NODE");
            if (rpcSolidityNodeStr != null && !rpcSolidityNodeStr.isEmpty()) {
                rpcSolidityNode = Integer.parseInt(rpcSolidityNodeStr);
            }
            
            // Configure event plugin if enabled
            if (configEventPluginEnabled) {
                String kafkaServer = getEnv("EVENT_PLUGIN_KAFKA_SERVER");
                if (kafkaServer != null && !kafkaServer.isEmpty()) {
                    configEventPluginKafkaServer = kafkaServer;
                }
                if (configEventPluginKafkaServer == null || configEventPluginKafkaServer.isEmpty()) {
                    System.err.println("EVENT_PLUGIN_KAFKA_SERVER must be specified when event plugin is enabled");
                    System.exit(1);
                }
                
                esFlag = "--es";
                configEventPluginPath = "/usr/local/tron/plugins/plugin-kafka-1.0.0.zip";
                
                // Validate and set trigger flags
                String blockTrigger = getEnv("EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED", blockTrigger);
                if (blockTrigger != null && !blockTrigger.isEmpty()) {
                    configBlockTriggerEnabled = Boolean.parseBoolean(blockTrigger);
                }
                
                String transactionTrigger = getEnv("EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED", transactionTrigger);
                if (transactionTrigger != null && !transactionTrigger.isEmpty()) {
                    configTransactionTriggerEnabled = Boolean.parseBoolean(transactionTrigger);
                }
                
                String contracteventTrigger = getEnv("EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED", contracteventTrigger);
                if (contracteventTrigger != null && !contracteventTrigger.isEmpty()) {
                    configContracteventTriggerEnabled = Boolean.parseBoolean(contracteventTrigger);
                }
                
                String contractlogTrigger = getEnv("EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED", contractlogTrigger);
                if (contractlogTrigger != null && !contractlogTrigger.isEmpty()) {
                    configContractlogTriggerEnabled = Boolean.parseBoolean(contractlogTrigger);
                }
                
                String solidityBlockTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED", solidityBlockTrigger);
                if (solidityBlockTrigger != null && !solidityBlockTrigger.isEmpty()) {
                    configSolidityBlockTriggerEnabled = Boolean.parseBoolean(solidityBlockTrigger);
                }
                
                String solidityEventTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED", solidityEventTrigger);
                if (solidityEventTrigger != null && !solidityEventTrigger.isEmpty()) {
                    configSolidityEventTriggerEnabled = Boolean.parseBoolean(solidityEventTrigger);
                }
                
                String solidityLogTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED", solidityLogTrigger);
                if (solidityLogTrigger != null && !solidityLogTrigger.isEmpty()) {
                    configSolidityLogTriggerEnabled = Boolean.parseBoolean(solidityLogTrigger);
                }
                
                // Build filter strings
                String addressFilter = buildFilter("EVENT_PLUGIN_ADDRESS_FILTER");
                if (addressFilter != null) {
                    configContractAddressFilter = addressFilter;
                }
                
                String topicFilter = buildFilter("EVENT_PLUGIN_TOPIC_FILTER");
                if (topicFilter != null) {
                    configContractTopicFilter = topicFilter;
                }
            }
            
            // Update config file with replacements
            Path configPath = Paths.get(configFile);
            String content = new String(Files.readAllBytes(configPath));
            
            // Regex replacements (like sed -i)
            content = content.replaceAll("listen\\.port = .*", "listen.port = " + configP2pPort);
            content = content.replaceAll("fullNodePort = .*", "fullNodePort = " + configFullNodePort);
            if (configSolidityNodePort != null) {
                content = content.replaceAll("solidityPort = .*", "solidityPort = " + configSolidityNodePort);
            }
            
            // Placeholder replacements
            content = content.replace("{VM_MAX_TIME_RATIO_PLACEHOLDER}", String.valueOf(configVmMaxTimeRatio));
            content = content.replace("{PLUGIN_PATH_PLACEHOLDER}", configEventPluginPath);
            content = content.replace("{KAFKA_SERVER_PLACEHOLDER}", configEventPluginKafkaServer);
            content = content.replace("{BLOCK_TRIGGER_PLACEHOLDER}", String.valueOf(configBlockTriggerEnabled));
            content = content.replace("{TRANSACTION_TRIGGER_PLACEHOLDER}", String.valueOf(configTransactionTriggerEnabled));
            content = content.replace("{CONTRACTEVENT_TRIGGER_PLACEHOLDER}", String.valueOf(configContracteventTriggerEnabled));
            content = content.replace("{CONTRACTLOG_TRIGGER_PLACEHOLDER}", String.valueOf(configContractlogTriggerEnabled));
            content = content.replace("{SOLIDITY_BLOCK_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityBlockTriggerEnabled));
            content = content.replace("{SOLIDITY_EVENT_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityEventTriggerEnabled));
            content = content.replace("{SOLIDITY_LOG_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityLogTriggerEnabled));
            content = content.replace("{CONTRACT_ADDRESS_FILTER_PLACEHOLDER}", configContractAddressFilter);
            content = content.replace("{CONTRACT_TOPIC_FILTER_PLACEHOLDER}", configContractTopicFilter);
            content = content.replace("{RPC_FULL_NODE}", String.valueOf(rpcFullNode));
            content = content.replace("{RPC_SOLIDITY_NODE}", String.valueOf(rpcSolidityNode));
            content = content.replace("{FULL_NODE_PORT}", String.valueOf(configFullNodePort));
            if (configSolidityNodePort != null) {
                content = content.replace("{SOLIDITY_NODE_PORT}", configSolidityNodePort);
            }
            
            Files.write(configPath, content.getBytes());
            
            // Build and execute Java command based on network
            String javaOptsCommon = "-XX:ReservedCodeCacheSize=256m -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=2G -XX:MaxDirectMemorySize=2G -XX:+HeapDumpOnOutOfMemoryError -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+UseConcMarkSweepGC -XX:NewRatio=2 -XX:+CMSScavengeBeforeRemark -XX:+ParallelRefProcEnabled -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70 -XX:-UseNUMA";
            
            String javaOpts;
            if (network == null || network.isEmpty() || "mainnet".equals(network)) {
                javaOpts = javaOptsCommon + " -Xms16G -Xmx16G -XX:+AlwaysPreTouch";
            } else {
                javaOpts = javaOptsCommon + " -Xms8G -Xmx8G -XX:-AlwaysPreTouch";
            }
            
            // Build command
            List<String> command = new ArrayList<>();
            command.add("java");
            // Split javaOpts and add each as separate argument
            for (String opt : javaOpts.split("\\s+")) {
                if (!opt.isEmpty()) {
                    command.add(opt);
                }
            }
            command.add("-jar");
            command.add("/usr/local/tron/FullNode.jar");
            command.add("-c");
            command.add(configFile);
            command.add("-d");
            command.add("/data");
            if (!esFlag.isEmpty()) {
                command.add(esFlag);
            }
            if (!witnessFlag.isEmpty()) {
                command.add(witnessFlag);
            }
            
            System.out.println("Executing: " + String.join(" ", command));
            
            // Execute the command
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}