#!/bin/bash

# Default configuration values
CONFIG_FILE=/etc/tron/mainnet_config.conf
CONFIG_P2P_PORT=28888
CONFIG_FULL_NODE_PORT=8090
CONFIG_VM_MAX_TIME_RATIO=20.0
RPC_FULL_NODE=8545
RPC_SOLIDITY_NODE=8555

# Event plugin defaults (mainnet)
CONFIG_EVENT_PLUGIN_ENABLED=true
CONFIG_EVENT_PLUGIN_PATH=""
CONFIG_EVENT_PLUGIN_KAFKA_SERVER="kafka:9092"
CONFIG_BLOCK_TRIGGER_ENABLED=true
CONFIG_TRANSACTION_TRIGGER_ENABLED=true
CONFIG_CONTRACTEVENT_TRIGGER_ENABLED=true
CONFIG_CONTRACTLOG_TRIGGER_ENABLED=false
CONFIG_SOLIDITY_BLOCK_TRIGGER_ENABLED=false
CONFIG_SOLIDITY_EVENT_TRIGGER_ENABLED=false
CONFIG_SOLIDITY_LOG_TRIGGER_ENABLED=false
CONFIG_CONTRACT_ADDRESS_FILTER="\"\""
CONFIG_CONTRACT_TOPIC_FILTER="\"\""

ES_FLAG=""
WITNESS_FLAG=""

# Helper function to validate boolean environment variables
validate_boolean() {
  local var_name=$1
  local var_value=${!var_name}
  if [[ -n "${var_value}" ]] && [[ "${var_value}" != "true" ]] && [[ "${var_value}" != "false" ]]; then
    echo "Invalid ${var_name}: ${var_value}. Must be one of: \"true\", \"false\""
    exit 1
  fi
}

# Helper function to build filter string from space-separated values
build_filter() {
  local env_var=$1
  local config_var=$2
  local value=${!env_var}
  if [[ -n "${value}" ]]; then
    local result=""
    IFS=" " read -ra items <<< "${value}"
    local last_idx=$((${#items[@]} - 1))
    for idx in "${!items[@]}"; do
      if [[ "$idx" == "$last_idx" ]]; then
        result="${result}\"${items[$idx]}\""
      else
        result="${result}\"${items[$idx]}\",\n      "
      fi
    done
    eval "${config_var}=\"${result}\""
  fi
}

# Validate network
if [[ -n "${NETWORK}" ]] && [[ "${NETWORK}" != "mainnet" ]] && [[ "${NETWORK}" != "nile" ]]; then
  echo "Invalid NETWORK: ${NETWORK}. Must be one of: \"mainnet\", \"nile\""
  exit 1
fi

# Set config file based on network
[[ "${NETWORK}" == "nile" ]] && CONFIG_FILE=/etc/tron/nile_config.conf

# Validate and set witness mode
validate_boolean "WITNESS_MODE"
[[ "${WITNESS_MODE}" == "true" ]] && WITNESS_FLAG="--witness"

# Validate and set event plugin (override defaults if env var is set)
validate_boolean "EVENT_PLUGIN_ENABLED"
[[ -n "${EVENT_PLUGIN_ENABLED}" ]] && CONFIG_EVENT_PLUGIN_ENABLED=${EVENT_PLUGIN_ENABLED}

# Override ports and settings from environment variables
[[ -n "${P2P_PORT}" ]] && CONFIG_P2P_PORT=${P2P_PORT}
[[ -n "${FULL_NODE_PORT}" ]] && CONFIG_FULL_NODE_PORT=${FULL_NODE_PORT}
[[ -n "${SOLIDITY_NODE_PORT}" ]] && CONFIG_SOLIDITY_NODE_PORT=${SOLIDITY_NODE_PORT}
[[ -n "${VM_MAX_TIME_RATIO}" ]] && CONFIG_VM_MAX_TIME_RATIO=${VM_MAX_TIME_RATIO}
[[ -n "${RPC_FULL_NODE}" ]] && RPC_FULL_NODE=${RPC_FULL_NODE}
[[ -n "${RPC_SOLIDITY_NODE}" ]] && RPC_SOLIDITY_NODE=${RPC_SOLIDITY_NODE}

# Configure event plugin if enabled
if [[ "${CONFIG_EVENT_PLUGIN_ENABLED}" == "true" ]]; then
  # Override Kafka server if provided, otherwise use default
  [[ -n "${EVENT_PLUGIN_KAFKA_SERVER}" ]] && CONFIG_EVENT_PLUGIN_KAFKA_SERVER=${EVENT_PLUGIN_KAFKA_SERVER}
  [[ -z "${CONFIG_EVENT_PLUGIN_KAFKA_SERVER}" ]] && { echo "EVENT_PLUGIN_KAFKA_SERVER must be specified when event plugin is enabled"; exit 1; }
  
  ES_FLAG="--es"
  CONFIG_EVENT_PLUGIN_PATH='\/usr\/local\/tron\/plugins\/plugin-kafka-1.0.0.zip'

  # Validate and set trigger flags (override defaults if env vars are set)
  validate_boolean "EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED}" ]] && CONFIG_BLOCK_TRIGGER_ENABLED=${EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED}" ]] && CONFIG_TRANSACTION_TRIGGER_ENABLED=${EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED}" ]] && CONFIG_CONTRACTEVENT_TRIGGER_ENABLED=${EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED}" ]] && CONFIG_CONTRACTLOG_TRIGGER_ENABLED=${EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED}" ]] && CONFIG_SOLIDITY_BLOCK_TRIGGER_ENABLED=${EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED}" ]] && CONFIG_SOLIDITY_EVENT_TRIGGER_ENABLED=${EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED}
  
  validate_boolean "EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED"
  [[ -n "${EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED}" ]] && CONFIG_SOLIDITY_LOG_TRIGGER_ENABLED=${EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED}

  # Build filter strings
  build_filter "EVENT_PLUGIN_ADDRESS_FILTER" "CONFIG_CONTRACT_ADDRESS_FILTER"
  build_filter "EVENT_PLUGIN_TOPIC_FILTER" "CONFIG_CONTRACT_TOPIC_FILTER"
fi

# Update config file with sed replacements
sed -i -e "s/listen.port = .*/listen.port = ${CONFIG_P2P_PORT}/g" \
       -e "s/fullNodePort = .*/fullNodePort = ${CONFIG_FULL_NODE_PORT}/g" \
       -e "s/solidityPort = .*/solidityPort = ${CONFIG_SOLIDITY_NODE_PORT}/g" \
       -e "s/{VM_MAX_TIME_RATIO_PLACEHOLDER}/${CONFIG_VM_MAX_TIME_RATIO}/g" \
       -e "s/{PLUGIN_PATH_PLACEHOLDER}/${CONFIG_EVENT_PLUGIN_PATH}/g" \
       -e "s/{KAFKA_SERVER_PLACEHOLDER}/${CONFIG_EVENT_PLUGIN_KAFKA_SERVER}/g" \
       -e "s/{BLOCK_TRIGGER_PLACEHOLDER}/${CONFIG_BLOCK_TRIGGER_ENABLED}/g" \
       -e "s/{TRANSACTION_TRIGGER_PLACEHOLDER}/${CONFIG_TRANSACTION_TRIGGER_ENABLED}/g" \
       -e "s/{CONTRACTEVENT_TRIGGER_PLACEHOLDER}/${CONFIG_CONTRACTEVENT_TRIGGER_ENABLED}/g" \
       -e "s/{CONTRACTLOG_TRIGGER_PLACEHOLDER}/${CONFIG_CONTRACTLOG_TRIGGER_ENABLED}/g" \
       -e "s/{SOLIDITY_BLOCK_TRIGGER_PLACEHOLDER}/${CONFIG_SOLIDITY_BLOCK_TRIGGER_ENABLED}/g" \
       -e "s/{SOLIDITY_EVENT_TRIGGER_PLACEHOLDER}/${CONFIG_SOLIDITY_EVENT_TRIGGER_ENABLED}/g" \
       -e "s/{SOLIDITY_LOG_TRIGGER_PLACEHOLDER}/${CONFIG_SOLIDITY_LOG_TRIGGER_ENABLED}/g" \
       -e "s/{CONTRACT_ADDRESS_FILTER_PLACEHOLDER}/${CONFIG_CONTRACT_ADDRESS_FILTER}/g" \
       -e "s/{CONTRACT_TOPIC_FILTER_PLACEHOLDER}/${CONFIG_CONTRACT_TOPIC_FILTER}/g" \
       -e "s/{RPC_FULL_NODE}/${RPC_FULL_NODE}/g" \
       -e "s/{RPC_SOLIDITY_NODE}/${RPC_SOLIDITY_NODE}/g" \
       ${CONFIG_FILE}

# Build and execute Java command based on network
JAVA_OPTS_COMMON="-XX:ReservedCodeCacheSize=256m -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=2G -XX:MaxDirectMemorySize=2G -XX:+HeapDumpOnOutOfMemoryError -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+UseConcMarkSweepGC -XX:NewRatio=2 -XX:+CMSScavengeBeforeRemark -XX:+ParallelRefProcEnabled -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70 -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap"

if [[ -z "${NETWORK}" ]] || [[ "${NETWORK}" == "mainnet" ]]; then
  JAVA_OPTS="${JAVA_OPTS_COMMON} -Xms16G -Xmx16G -XX:+UseNUMA -XX:+AlwaysPreTouch"
elif [[ "${NETWORK}" == "nile" ]]; then
  JAVA_OPTS="${JAVA_OPTS_COMMON} -Xms8G -Xmx8G -XX:-UseNUMA -XX:-AlwaysPreTouch"
fi

COMMAND="java ${JAVA_OPTS} -jar /usr/local/tron/FullNode.jar -c ${CONFIG_FILE} -d /data ${ES_FLAG} ${WITNESS_FLAG}"
echo ${COMMAND}
exec ${COMMAND}