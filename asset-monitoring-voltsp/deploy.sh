#!/bin/bash
# Deployment script for Asset Monitoring VoltSP Pipelines

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Asset Monitoring VoltSP Pipeline Deployment${NC}"
echo "============================================="

# Check prerequisites
echo -e "\n${YELLOW}Checking prerequisites...${NC}"

if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven not found${NC}"
    exit 1
fi

if ! command -v kubectl &> /dev/null; then
    echo -e "${YELLOW}Warning: kubectl not found (required for Kubernetes deployment)${NC}"
fi

if ! command -v helm &> /dev/null; then
    echo -e "${YELLOW}Warning: helm not found (required for Kubernetes deployment)${NC}"
fi

# Build pipeline
echo -e "\n${YELLOW}Building pipeline JAR...${NC}"
mvn clean package

if [ ! -f "target/asset-monitoring-voltsp-1.0.0.jar" ]; then
    echo -e "${RED}Error: Build failed, JAR not found${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful${NC}"

# Deployment mode selection
echo -e "\n${YELLOW}Select deployment mode:${NC}"
echo "1) Kubernetes (Helm)"
echo "2) Bare Metal (VoltSP CLI)"
echo "3) Build only (skip deployment)"
read -p "Enter choice [1-3]: " choice

case $choice in
    1)
        # Kubernetes deployment
        echo -e "\n${YELLOW}Deploying to Kubernetes...${NC}"

        # Check for license file
        if [ -z "$VOLTDB_LICENSE" ]; then
            read -p "Enter path to VoltDB license file: " VOLTDB_LICENSE
        fi

        if [ ! -f "$VOLTDB_LICENSE" ]; then
            echo -e "${RED}Error: License file not found: $VOLTDB_LICENSE${NC}"
            exit 1
        fi

        # Add Helm repo
        echo -e "\n${YELLOW}Adding VoltDB Helm repository...${NC}"
        helm repo add voltdb https://voltdb-kubernetes-charts.storage.googleapis.com 2>/dev/null || true
        helm repo update

        # Deploy main pipeline
        echo -e "\n${YELLOW}Deploying main pipeline...${NC}"
        helm install asset-monitoring voltdb/volt-streams \
            --set-file streaming.licenseXMLFile=${VOLTDB_LICENSE} \
            --set-file streaming.voltapps=target/asset-monitoring-voltsp-1.0.0.jar \
            --values helm/values-main-pipeline.yaml

        echo -e "${GREEN}✓ Main pipeline deployed${NC}"

        # Deploy rolling window pipeline
        echo -e "\n${YELLOW}Deploying rolling window pipeline...${NC}"
        helm install rolling-window voltdb/volt-streams \
            --set-file streaming.licenseXMLFile=${VOLTDB_LICENSE} \
            --set-file streaming.voltapps=target/asset-monitoring-voltsp-1.0.0.jar \
            --values helm/values-rolling-window.yaml

        echo -e "${GREEN}✓ Rolling window pipeline deployed${NC}"

        # Show status
        echo -e "\n${YELLOW}Deployment status:${NC}"
        kubectl get pods -l app.kubernetes.io/instance=asset-monitoring
        kubectl get pods -l app.kubernetes.io/instance=rolling-window

        echo -e "\n${GREEN}Deployment complete!${NC}"
        echo -e "\nView logs with:"
        echo "  kubectl logs -f deployment/asset-monitoring"
        echo "  kubectl logs -f deployment/rolling-window"
        ;;

    2)
        # Bare metal deployment
        echo -e "\n${YELLOW}Bare metal deployment${NC}"

        if ! command -v voltsp &> /dev/null; then
            echo -e "${RED}Error: voltsp CLI not found${NC}"
            exit 1
        fi

        # Check for license file
        if [ -z "$VOLTDB_LICENSE" ]; then
            read -p "Enter path to VoltDB license file: " VOLTDB_LICENSE
        fi

        if [ ! -f "$VOLTDB_LICENSE" ]; then
            echo -e "${RED}Error: License file not found: $VOLTDB_LICENSE${NC}"
            exit 1
        fi

        # Set environment variables
        export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
        export VOLTDB_HOST="${VOLTDB_HOST:-localhost}"
        export VOLTDB_PORT="${VOLTDB_PORT:-21211}"

        echo -e "\n${YELLOW}Configuration:${NC}"
        echo "  Kafka: $KAFKA_BOOTSTRAP_SERVERS"
        echo "  VoltDB: $VOLTDB_HOST:$VOLTDB_PORT"

        read -p "Continue with deployment? [y/N]: " confirm
        if [[ ! $confirm =~ ^[Yy]$ ]]; then
            echo "Deployment cancelled"
            exit 0
        fi

        # Deploy main pipeline (background)
        echo -e "\n${YELLOW}Starting main pipeline...${NC}"
        voltsp run \
            --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
            --class com.example.voltsp.AssetMonitoringPipeline \
            --config configuration.yaml \
            --configSecure configurationSecure.yaml \
            --license $VOLTDB_LICENSE &

        MAIN_PID=$!
        echo -e "${GREEN}✓ Main pipeline started (PID: $MAIN_PID)${NC}"

        # Deploy rolling window pipeline (background)
        echo -e "\n${YELLOW}Starting rolling window pipeline...${NC}"
        voltsp run \
            --voltapp target/asset-monitoring-voltsp-1.0.0.jar \
            --class com.example.voltsp.RollingWindowPipeline \
            --config configuration-rolling-window.yaml \
            --license $VOLTDB_LICENSE &

        WINDOW_PID=$!
        echo -e "${GREEN}✓ Rolling window pipeline started (PID: $WINDOW_PID)${NC}"

        echo -e "\n${GREEN}Deployment complete!${NC}"
        echo -e "\nTo stop pipelines:"
        echo "  kill $MAIN_PID $WINDOW_PID"
        ;;

    3)
        echo -e "\n${GREEN}Build complete, skipping deployment${NC}"
        ;;

    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

echo -e "\n${GREEN}Done!${NC}"
