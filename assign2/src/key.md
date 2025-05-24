#!/bin/bash

# SSL Certificate Setup Script for Java Chat Application
# This script generates the necessary SSL certificates and keystores

echo "Setting up SSL certificates for Java Chat Application..."

# Clean up any existing keystores
echo "Cleaning up existing keystores..."
rm -f server.jks truststore.jks server.crt

# Generate server keystore with private key and certificate
echo "Generating server keystore..."
keytool -genkeypair \
-alias server \
-keyalg RSA \
-keysize 2048 \
-validity 365 \
-keystore server.jks \
-storepass changeit \
-keypass changeit \
-dname "CN=localhost, OU=ChatApp, O=MyOrg, L=City, ST=State, C=US"

if [ $? -ne 0 ]; then
echo "ERROR: Failed to generate server keystore"
exit 1
fi

# Export server certificate
echo "Exporting server certificate..."
keytool -exportcert \
-alias server \
-keystore server.jks \
-storepass changeit \
-file server.crt

if [ $? -ne 0 ]; then
echo "ERROR: Failed to export server certificate"
exit 1
fi

# Create truststore and import server certificate
echo "Creating truststore..."
keytool -importcert \
-alias server \
-file server.crt \
-keystore truststore.jks \
-storepass changeit \
-noprompt

if [ $? -ne 0 ]; then
echo "ERROR: Failed to create truststore"
exit 1
fi

# Verify keystores
echo "Verifying keystores..."
echo "Server keystore contents:"
keytool -list -keystore server.jks -storepass changeit

echo -e "\nTruststore contents:"
keytool -list -keystore truststore.jks -storepass changeit

# Clean up temporary certificate file
rm -f server.crt

echo -e "\nSSL setup complete!"
echo "Generated files:"
echo "  - server.jks (server keystore with private key)"
echo "  - truststore.jks (client truststore with server certificate)"
echo -e "\nBoth keystores use password: changeit"
echo -e "\nYou can now run your Server and Client applications."